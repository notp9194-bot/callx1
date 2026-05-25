package com.callx.app.cache;

/**
 * Priority levels for the multi-tier cache system.
 * HIGH  → RAM only
 * MEDIUM → RAM + Room DB
 * LOW   → Room DB / Disk only
 */
public enum CachePriority {
    HIGH,
    MEDIUM,
    LOW
}
