package com.tudominio.parentalcontrol.data.model

import com.tudominio.parentalcontrol.domain.Policy

sealed interface PolicySnapshotState {
    data object Empty : PolicySnapshotState
    data class Valid(val policy: Policy) : PolicySnapshotState
    data class Invalid(val reason: String) : PolicySnapshotState
}
