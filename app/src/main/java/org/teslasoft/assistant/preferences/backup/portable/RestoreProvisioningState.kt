/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.backup.portable

import java.io.File
import org.json.JSONObject

/** Tiny non-sensitive rollback marker: whether a category's database existed
 * before staging. It lets process-death recovery remove a database that the
 * interrupted restore itself created instead of leaving an empty new store. */
internal object RestoreProvisioningState {
    private const val FILE = "provisioning.json"

    fun write(root: File, provisioned: Boolean): Boolean = try {
        File(root, FILE).writeText(
            JSONObject().put("version", 1).put("provisioned", provisioned).toString(),
            Charsets.UTF_8
        )
        read(root) == provisioned
    } catch (_: Exception) {
        false
    }

    fun read(root: File): Boolean? = try {
        val file = File(root, FILE)
        if (!file.isFile || file.length() > 1024L) return null
        val json = JSONObject(file.readText(Charsets.UTF_8))
        if (json.optInt("version", -1) != 1 || !json.has("provisioned")) null
        else json.getBoolean("provisioned")
    } catch (_: Exception) {
        null
    }
}
