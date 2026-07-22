/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.preferences.backup

/**
 * Thrown by [org.teslasoft.assistant.preferences.memory.MemoryStore.getInstance]
 * and [org.teslasoft.assistant.preferences.lorebook.LoreBookStore.getInstance]
 * while that database's degraded flag is set (Build Phase 3 item 1): a store
 * with CONFIRMED damage is genuinely off — no reads, no writes — until a
 * repair or restore succeeds, because reading a corrupt SQLite file is itself
 * unsafe (§15.2a).
 *
 * Extends IllegalStateException so every existing best-effort call site that
 * already catches the locked-key IllegalStateException (the enforcer, the
 * librarian, transcript capture, exporter, lorebook injection) degrades the
 * same way it always has — memory/lore quietly absent for that operation,
 * generation never blocked. The distinct type exists so UI surfaces can tell
 * "degraded pending repair" apart from "Keystore locked" and route the user
 * to the repair flow instead of a retry.
 */
class DatabaseDegradedException(val type: BackupType) :
    IllegalStateException("${type.key} database is disabled pending repair (confirmed damage)")
