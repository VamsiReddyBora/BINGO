package com.bingo.multiplayer.domain.network

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest

internal const val BROKER_WARNING_GRACE_MILLIS = 10_000L

/** Presentation policy only; MQTT health and connection attempts remain independent. */
internal fun brokerWarningVisibility(
    internetAvailable: Flow<Boolean>,
    foreground: Flow<Boolean>,
    brokerDown: Flow<Boolean>
): Flow<Boolean> = combine(internetAvailable, foreground, brokerDown) { online, active, down ->
    online && active && down
}.distinctUntilChanged().transformLatest { eligible ->
    emit(false)
    if (eligible) {
        delay(BROKER_WARNING_GRACE_MILLIS)
        emit(true)
    }
}.distinctUntilChanged()
