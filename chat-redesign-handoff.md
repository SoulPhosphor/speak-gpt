<!--
Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.

Licensed under the Apache License, Version 2.0.
-->

# Phase 4 Chat Redesign — Implementation Handoff

This is the short entry point for an AI implementation session.

## Objective

Implement the complete owner-approved Phase 4 chat redesign without reopening settled product/design decisions.

## Start here

Read `chat-redesign-implementation-playbook.md` first. It defines the seven implementation phases, checkpoint/CI rules, stop conditions, and definition of done.

Then read the authoritative design/source documents in the playbook's mandatory-reading order.

## Execution rule

Work through the playbook phases **in order**. Complete, verify, and commit each phase before beginning the next. Ordinary implementation details should be resolved by inspecting current code. Ask the owner only for a genuine new product decision, destructive migration risk, or conflict between authoritative requirements and current code.

Do not combine all Phase 4 work into one giant unreviewable commit.

## Important owner constraints

- The owner does not need to manually research provider reasoning schemas. That verification is part of Phase 5 implementation.
- The owner is not doing palette/theme design now. Build the redesign theme-ready using shared/theme-aware roles and resources without inventing future palettes.
- End-to-end image-generation smoke testing may remain a clearly documented follow-up if the owner does not want to test it during this implementation pass.
- Auto-save Chats is out of scope and must remain untouched.
- Existing voice, streaming, keyboard, attachments, images, message actions, and other load-bearing chat behavior must be preserved unless the plans explicitly replace a presentation detail.

## Suggested implementation-session prompt

> Implement the Phase 4 chat redesign from the repository plans. Read `chat-redesign-handoff.md` and `chat-redesign-implementation-playbook.md`, then follow the mandatory document order. Work through the seven phases in order. Commit and verify each phase before continuing. Make implementation-level decisions yourself by inspecting current code. Do not reopen owner-approved design decisions. Stop and ask me only for a genuine product decision, destructive migration risk, or conflict between the plan and current code.
