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
 * Pure 3-strikes decision (Build Phase 3 item 8; unit-tested): what the UI
 * does about a type's consecutive backup failures. Category-split by owner
 * approval (July 20 2026):
 *  - destination/verify failures → the storage dialog (`Change Backup Folder
 *    | Retry | Cancel`) — they must NEVER mark the source degraded or
 *    suggest repair;
 *  - a SOURCE failure → no storage dialog; run the store-specific integrity
 *    check and route by type — only CONFIRMED damage escalates further;
 *  - fewer than [STRIKES] failures, or no recorded category → nothing beyond
 *    the status row.
 */
object BackupFailurePolicy {

    const val STRIKES = 3

    enum class Response { NONE, STORAGE_DIALOG, SOURCE_CHECK }

    fun respond(consecutiveFailures: Int, category: BackupFailureCategory?): Response {
        if (consecutiveFailures < STRIKES || category == null) return Response.NONE
        return when (category) {
            BackupFailureCategory.DESTINATION_PERMISSION,
            BackupFailureCategory.DESTINATION_WRITE,
            BackupFailureCategory.VERIFY -> Response.STORAGE_DIALOG
            BackupFailureCategory.SOURCE -> Response.SOURCE_CHECK
        }
    }
}
