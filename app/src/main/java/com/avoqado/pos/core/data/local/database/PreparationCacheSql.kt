package com.avoqado.pos.core.data.local.database

/**
 * Private delivery records are written by PreparationPeerProtocol.json, compact with defaults.
 * Android 26–32 system SQLite may lack JSON1. Search exact serialized scalar tokens instead;
 * quotes inside user text are escaped, and cannot act as these top-level job fields.
 * Full JSON decoding still validates every selected record before any delivery/print action.
 */
object PreparationCacheSql {
    private const val SCOPE = "venue_id = :venueId AND cache_key >= :prefix AND cache_key < (:prefix || char(65535))"
    private const val PAPER = "(instr(json, '\"paperState\":\"QUEUED\"') > 0 OR instr(json, '\"paperState\":\"PRINTING\"') > 0 OR instr(json, '\"paperState\":\"FAILED\"') > 0 OR instr(json, '\"paperState\":\"UNCERTAIN\"') > 0 OR instr(json, '\"paperState\":\"REVIEW\"') > 0)"
    const val DELIVERIES = "SELECT * FROM cached_payloads WHERE $SCOPE AND cache_key > :after AND instr(json, '\"done\":true') = 0 ORDER BY cache_key ASC LIMIT 50"
    const val PAPER_COUNT = "SELECT COUNT(*) FROM cached_payloads WHERE $SCOPE AND $PAPER"
    const val PAPER_PAGE = "SELECT * FROM cached_payloads WHERE $SCOPE AND cache_key > :after AND $PAPER ORDER BY cache_key ASC LIMIT 21"
    private const val LINES = "$SCOPE AND instr(json, '\"base\":{') > 0 AND instr(json, :orderToken) > 0 AND (instr(json, '\"draftRoundKey\":') = 0 OR instr(json, '\"draftRoundKey\":null') > 0)"
    const val LINE_COUNT = "SELECT COUNT(*) FROM cached_payloads WHERE $LINES"
    const val LINE_PAGE = "SELECT * FROM cached_payloads WHERE $LINES AND cache_key > :after ORDER BY cache_key ASC LIMIT 51"
}
