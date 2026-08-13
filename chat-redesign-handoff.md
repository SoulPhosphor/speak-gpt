<!--
Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.

Licensed under the Apache License, Version 2.0.
-->

# Chat Redesign Handoff

Use this file only as the short entry point for implementation.

## Authority

- **Design/product authority:** `chat-redesign-plan.md`
- **Execution order:** `chat-redesign-implementation-playbook.md`
- **Repository safety:** `CLAUDE.md`

There are no dated chat-redesign addenda and no separate chat-redesign index. Future approved design changes update `chat-redesign-plan.md` directly.

The app-wide style guide remains separate from the chat-redesign package. Consult it only when the current implementation work directly touches shared overall-app styles/theme roles.

## Resume rule

If an implementation branch/workspace already exists, continue from its current checkpoint. Do not restart, re-clone, re-checkpoint, reconstruct, or broadly re-audit completed work.

Read the plan once, then read only the current implementation phase. Inspect only files directly relevant to that phase.

## Core implementation rule

Choose the **smallest safe implementation** that satisfies the plan.

Do not reopen approved design decisions. Do not invent new product decisions. Do not redesign unrelated systems or perform repository-wide cleanup.

The chat architecture is intentionally simple:

- one shared chat behavior/data system;
- one current adaptable presentation shell;
- no second shell or shell framework now;
- keep the behavior/data boundary clean enough that a different second presentation shell can be added later without duplicating the chat engine.

Existing voice, streaming, keyboard, attachments, images, message actions, markdown, editing, and other load-bearing chat behavior must be preserved unless the plan explicitly replaces a presentation detail.

Stop for owner input only for a genuine new product decision, destructive migration risk, or an actual conflict between the authoritative plan and current code.