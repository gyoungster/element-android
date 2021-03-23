/*
 * Copyright (c) 2021 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session.room.timeline

import io.realm.OrderedCollectionChangeSet
import io.realm.OrderedRealmCollectionChangeListener
import io.realm.Realm
import io.realm.RealmChangeListener
import io.realm.RealmList
import io.realm.RealmQuery
import io.realm.RealmResults
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.TimelineSettings
import org.matrix.android.sdk.internal.database.mapper.TimelineEventMapper
import org.matrix.android.sdk.internal.database.model.ChunkEntity
import org.matrix.android.sdk.internal.database.model.ChunkEntityFields
import org.matrix.android.sdk.internal.database.model.RoomEntity
import org.matrix.android.sdk.internal.database.model.TimelineEventEntity
import org.matrix.android.sdk.internal.database.query.where
import java.util.concurrent.atomic.AtomicReference

internal interface LoadTimelineStrategy : TimelineInput.Listener {
    fun onStart()
    fun onStop()
    suspend fun loadMore(count: Long, direction: SimpleTimeline.Direction): LoadMoreResult
    fun buildSnapshot(): List<TimelineEvent>
    fun getBuiltEventIndex(eventId: String): Int?
}

internal class LiveTimelineStrategy(private val roomId: String,
                                   private val realm: AtomicReference<Realm>,
                                   private val timelineId: String,
                                   private val eventDecryptor: TimelineEventDecryptor,
                                   private val timelineInput: TimelineInput,
                                   private val paginationTask: PaginationTask,
                                   private val timelineEventMapper: TimelineEventMapper,
                                   private val onEventsUpdated: () -> Unit,
                                   private val onNewTimelineEvents: (List<String>) -> Unit)
    : LoadTimelineStrategy, UIEchoManager.Listener {

    private var roomEntity: RoomEntity? = null
    private var sendingTimelineEvents: RealmList<TimelineEventEntity>? = null
    private var chunkEntity: RealmResults<ChunkEntity>? = null
    private var timelineChunk: TimelineChunk? = null

    private val chunkEntityListener = OrderedRealmCollectionChangeListener { _: RealmResults<ChunkEntity>, changeSet: OrderedCollectionChangeSet ->
        val syncHasGap = changeSet.deletions.isNotEmpty() && changeSet.insertions.isNotEmpty()
        if (syncHasGap) {
            timelineChunk?.close(closeNext = false, closePrev = true)
            timelineChunk = chunkEntity?.createTimelineChunk()
            onEventsUpdated()
        }
    }

    private val sendingTimelineEventsListener = RealmChangeListener<RealmList<TimelineEventEntity>> { events ->
        uiEchoManager.onSentEventsInDatabase(events.map { it.eventId })
        onEventsUpdated()
    }

    private val uiEchoManager = UIEchoManager(TimelineSettings(10), this)

    override fun onStart() {
        timelineInput.listeners.add(this)
        val safeRealm = realm.get()
        roomEntity = RoomEntity.where(safeRealm, roomId = roomId).findFirst()
        sendingTimelineEvents = roomEntity?.sendingTimelineEvents
        sendingTimelineEvents?.addChangeListener(sendingTimelineEventsListener)
        chunkEntity = getChunkEntity(safeRealm).also {
            it.addChangeListener(chunkEntityListener)
            timelineChunk = it.createTimelineChunk()
        }
    }

    override fun onStop() {
        timelineInput.listeners.remove(this)
        chunkEntity?.removeChangeListener(chunkEntityListener)
        sendingTimelineEvents?.removeChangeListener(sendingTimelineEventsListener)
        timelineChunk?.close(closeNext = false, closePrev = true)
        sendingTimelineEvents = null
        roomEntity = null
        chunkEntity = null
        timelineChunk = null
    }

    override suspend fun loadMore(count: Long, direction: SimpleTimeline.Direction): LoadMoreResult {
        if (direction == SimpleTimeline.Direction.FORWARDS) {
            return LoadMoreResult.REACHED_END
        }
        return timelineChunk?.loadMore(count, direction) ?: LoadMoreResult.FAILURE
    }

    override fun buildSnapshot(): List<TimelineEvent> {
        return buildSendingEvents() + timelineChunk?.builtItems(includesNext = false, includesPrev = true).orEmpty()
    }

    override fun getBuiltEventIndex(eventId: String): Int? {
        return timelineChunk?.getBuiltEventIndex(eventId, searchInNext = false, searchInPrev = true)
    }

    private fun getChunkEntity(realm: Realm): RealmResults<ChunkEntity> {
        return ChunkEntity.where(realm, roomId)
                .equalTo(ChunkEntityFields.IS_LAST_FORWARD, true)
                .findAll()
    }

    private fun buildSendingEvents(): List<TimelineEvent> {
        val builtSendingEvents = mutableListOf<TimelineEvent>()
        uiEchoManager.getInMemorySendingEvents()
                .filterSendingEventsTo(builtSendingEvents)
        sendingTimelineEvents?.freeze()
                ?.filter { timelineEvent ->
                    builtSendingEvents.none { it.eventId == timelineEvent.eventId }
                }
                ?.map {
                    timelineEventMapper.map(it)
                }?.filterSendingEventsTo(builtSendingEvents)

        return builtSendingEvents
    }

    private fun RealmResults<ChunkEntity>.createTimelineChunk(): TimelineChunk? {
        return firstOrNull()?.let {
            TimelineChunk(
                    chunkEntity = it,
                    roomId = roomId,
                    paginationTask = paginationTask,
                    timelineEventMapper = timelineEventMapper,
                    uiEchoManager = uiEchoManager,
                    initialEventId = null,
                    onBuiltEvents = onEventsUpdated,
                    timelineId = timelineId,
                    eventDecryptor = eventDecryptor
            )
        }
    }

    override fun rebuildEvent(eventId: String, builder: (TimelineEvent) -> TimelineEvent?): Boolean {
        return timelineChunk?.rebuildEvent(eventId, builder, searchInNext = true, searchInPrev = true).orFalse()
    }

    override fun onLocalEchoCreated(roomId: String, timelineEvent: TimelineEvent) {
        if (roomId != this.roomId) {
            return
        }
        onNewTimelineEvents(listOf(timelineEvent.eventId))
        if (uiEchoManager.onLocalEchoCreated(timelineEvent)) {
            onEventsUpdated()
        }
    }

    override fun onLocalEchoUpdated(roomId: String, eventId: String, sendState: SendState) {
        if (roomId != this.roomId) {
            return
        }
        if (uiEchoManager.onSendStateUpdated(eventId, sendState)) {
            onEventsUpdated()
        }
    }

    override fun onNewTimelineEvents(roomId: String, eventIds: List<String>) {
        if (roomId == this.roomId) {
            onNewTimelineEvents(eventIds)
        }
    }

    private fun List<TimelineEvent>.filterSendingEventsTo(target: MutableList<TimelineEvent>) {
        target.addAll(
                map { uiEchoManager.updateSentStateWithUiEcho(it) }
        )
    }
}

internal class PastTimelineStrategy(
        private val roomId: String,
        private val originEventId: String,
        private val paginationTask: PaginationTask,
        private val builtItems: MutableList<TimelineEvent>) : LoadTimelineStrategy {

    override fun onStart() {
        TODO("Not yet implemented")
    }

    override fun onStop() {
        TODO("Not yet implemented")
    }

    private fun getQuery(): RealmQuery<TimelineEventEntity> {
        TODO("Not yet implemented")
    }

    override suspend fun loadMore(count: Long, direction: SimpleTimeline.Direction): LoadMoreResult {
        TODO("Not yet implemented")
    }

    override fun buildSnapshot(): List<TimelineEvent> {
        return emptyList()
    }

    override fun getBuiltEventIndex(eventId: String): Int? {
        TODO("Not yet implemented")
    }
}