package com.tudominio.parentalcontrol.auth

/**
 * Role of the device owner.
 *
 * Persisted alongside the synthetic auth token in `device_auth_prefs` so
 * that [DeviceAuthManager.getRole] can return the correct role after a
 * process restart. The names are part of the on-disk persistence contract
 * — renaming `PARENT` or `CHILD` would silently migrate existing installs
 * to [PARENT].
 *
 * Synthetic: the eventual `parent-auth-flow` change will replace
 * [DeviceAuthManager.authenticateOrCreate] with real sign-up/sign-in; the
 * `role` flag is the seam between the synthetic hotfix and the formal
 * flow.
 */
enum class Role {
    PARENT,
    CHILD
}
