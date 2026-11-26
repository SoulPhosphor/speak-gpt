# Conversation recall baseline

The evidence record for conversation recall (memory development plan G.15 and
G.16 Steps 14–16), the way `memory baseline.md` is the record for saved
memories. Step 14 records the first retrieval results. Step 15 adds measured
starting values and phone timings; Step 16 adds what is actually sent. Results
are never copied into the plan.

## Step 14: first retrieval baseline (2026-11-26)

### What was measured

- **What was found and what was left out** (G.15 points 1 and 2) for every
  question of the frozen Step 6 set (62 questions), the Step 14 additions
  (19 questions, 11 follow-up scenarios) and an independent check set
  (17 questions, 7 follow-up scenarios). Each question is scored on its own, as
  in Step 0. Nothing here is "what was sent": there is no assembly yet.
- **Engine:** retrieval stages 1–8 of G.6 as built (`conversation/recall/`),
  current chat only, no re-ranker, no stage 9.
- **Index:** each chat is cut, given search text (256-token target, 20-word
  overlap) and indexed by the real Step 8 updater; passages read under the
  model's document prefix with the speaker title.
- **Model:** EmbeddingGemma 300M q4, the pinned files Android Checks uses, with
  the app's own tokenizer (`HfTokenizer`) producing every token ID.
- **Runtimes.** The full evaluation below ran on desktop ONNX Runtime 1.29
  (`RecallEvaluationDesktopTest`, development machine). The phone's runtime
  (Android ONNX Runtime 1.20) ran the same evaluation for six of the chats on
  the CI emulator (`RecallEvaluationInstrumentedTest`, below). The emulator
  reads about one text every two seconds, so all 13 chats (about 730 texts)
  do not fit in a CI run.
- **Scoring rules.** "Found" counts expected exchanges in retrieval's list.
  "Wrongly added" counts every other exchange in the list; "must-not" counts
  those the sample names as unrelated. Recall's list is not limited to 3 here
  (assembly's limit, Step 16), so wrongly added exchanges below the best three
  are counted too. For chats sent whole, the plan's rule leaves out the last 3
  exchanges as already read by the AI. Retrieval is scored without that rule
  and its effect is reported separately: 23 expected exchanges (mostly in the
  short Step 14 chats) lie in a chat's last 3 exchanges.
- **Who wrote the samples.** The Step 6 set is frozen and unchanged. The
  Step 14 additions and the independent check set were written in this step,
  each before any run of it; their expected answers were never changed. The
  additions were used to choose the two value changes below; the
  independent check set never was.

### Values used

The approved G36 starting values (Owner, 2026-11-25), with two changes the
owner approved as provisional on 2026-11-26 after the first measurement:

| Value | Approved starting value | Working value | Why |
|---|---|---|---|
| Meaning: lowest similarity | 0.35 | **0.40** | Unrelated requests ("Tell me a joke about penguins", "Recommend a podcast") passed at 0.36–0.37 because everything else in the chat was even further away |
| Keywords: shared words must be rare in the chat | off | **on: together as rare as a word in at most 10% of the chat's passages** | "Why those two?" matched seven passages on the one word "two" |

**How the keyword rule differs from G.6's existing rules.** G.6 already drops
common words (a fixed list: "the", "what", "that", …) and weighs each
remaining word by how rare it is in the chat. That weighting is a *share of the
message's own words*: a message whose only remaining word is "two" covers 100%
of its own words in every passage that contains "two", however common "two"
is in the chat. The new rule adds an absolute bar: the words a passage shares
must together be about as rare as a word found in at most 10% of the chat's
passages. It matters mainly for short messages; a rare name ("Pepper",
"Coimbra") still passes alone. The common-word list, the share threshold
(0.5) and the rarity weighting are unchanged.

Saved-memory search is not affected: recall has its own values
(`RecallTuning`); `LiveRetrieval`, its calibrated values and every
saved-memory test are unchanged.

### Approved starting values and working values side by side

Questions and follow-ups (variant A titles). "Used to choose" is the Step 6
set plus the Step 14 additions; the independent check set was never used to
choose a value.

| Values | Set | Questions: found / missed / wrong (must-not) | Off follow-ups: found / wrong | Code-based: found / wrong | API AI: found / wrong | Unrelated requests: wrong (must-not), each mode |
|---|---|---|---|---|---|---|
| approved starting values | used to choose | 69 / 30 / 30 (6) | 4 / 10 | 4 / 10 | 6 / 12 | 4 (2) · 4 (2) · 4 (2) |
| approved starting values | independent check | 12 / 3 / 7 (4) | 2 / 0 | 3 / 1 | 3 / 1 | 0 (0) · 0 (0) · 1 (0) |
| starting values with floor 0.40 | used to choose | 69 / 30 / 30 (6) | 4 / 10 | 4 / 10 | 6 / 10 | 1 (1) · 1 (1) · 1 (1) |
| starting values with floor 0.40 | independent check | 12 / 3 / 5 (2) | 2 / 0 | 3 / 1 | 3 / 1 | 0 (0) · 0 (0) · 1 (0) |
| starting values with keyword rarity 10% | used to choose | 69 / 30 / 29 (5) | 4 / 2 | 4 / 2 | 6 / 4 | 4 (2) · 4 (2) · 4 (2) |
| starting values with keyword rarity 10% | independent check | 12 / 3 / 7 (4) | 2 / 0 | 3 / 1 | 3 / 1 | 0 (0) · 0 (0) · 1 (0) |
| working values (floor 0.40, keyword rarity 10%) | used to choose | 69 / 30 / 29 (5) | 4 / 2 | 4 / 2 | 6 / 2 | 1 (1) · 1 (1) · 1 (1) |
| working values (floor 0.40, keyword rarity 10%) | independent check | 12 / 3 / 5 (2) | 2 / 0 | 3 / 1 | 3 / 1 | 0 (0) · 0 (0) · 1 (0) |
| working values with certain 0.65 | used to choose | 66 / 33 / 21 (3) | 4 / 2 | 4 / 2 | 6 / 2 | 1 (1) · 1 (1) · 1 (1) |
| working values with certain 0.65 | independent check | 11 / 4 / 4 (2) | 2 / 0 | 3 / 1 | 3 / 1 | 0 (0) · 0 (0) · 1 (0) |
| working values with keyword rarity 5% | used to choose | 69 / 30 / 27 (5) | 4 / 2 | 4 / 2 | 6 / 2 | 1 (1) · 1 (1) · 1 (1) |
| working values with keyword rarity 5% | independent check | 12 / 3 / 5 (2) | 2 / 0 | 3 / 1 | 3 / 1 | 0 (0) · 0 (0) · 1 (0) |

- **Lowest similarity 0.40** is confirmed by the independent check: wrongly
  added 7 → 5 and must-not 4 → 2, with nothing lost; unrelated requests 4 → 1
  wrong in every mode. **Kept.**
- **The keyword rarity rule** removed 8 wrong additions from short follow-ups
  on the set used to choose it, lost nothing anywhere, and made **no
  difference** on the independent check set. Its benefit is shown only on
  the samples it was chosen on. **Kept as provisional.**
- **Certain-match bar 0.65** (not adopted): removes 8–9 wrong additions but
  also loses 3 correct exchanges on the chosen set and 1 on the independent
  set. This is an accuracy trade-off, left for Step 15 and the owner.
- **Keyword rarity 5%** (not adopted): 2 fewer wrong additions on the chosen
  set, no difference on the independent set. Not changed, to avoid tuning to
  the test chats.

### Totals by situation (working values, variant A titles)

| Set | Situation | Questions | Expected | Found | Missed | Already in the request | Wrongly added (of which must-not) |
|---|---|---:|---:|---:|---:|---:|---:|
| Independent check | common word only | 1 | 0 | 0 | 0 | 0 | 2 (1) |
| Independent check | decision changed | 2 | 4 | 3 | 1 | 0 | 0 (0) |
| Independent check | exact detail far back | 2 | 2 | 2 | 0 | 0 | 1 (1) |
| Independent check | names identity | 5 | 5 | 3 | 2 | 0 | 1 (0) |
| Independent check | nothing relevant | 3 | 0 | 0 | 0 | 0 | 0 (0) |
| Independent check | topic without name | 2 | 2 | 2 | 0 | 0 | 1 (0) |
| Independent check | unusual wording | 2 | 2 | 2 | 0 | 0 | 0 (0) |
| Step 14 additions | exact repeat | 1 | 1 | 1 | 0 | 0 | 2 (0) |
| Step 14 additions | long repetitive then correction | 2 | 4 | 3 | 1 | 0 | 0 (0) |
| Step 14 additions | names identity | 8 | 12 | 7 | 5 | 0 | 3 (1) |
| Step 14 additions | nothing relevant | 1 | 0 | 0 | 0 | 0 | 0 (0) |
| Step 14 additions | opposite outcomes | 2 | 4 | 4 | 0 | 0 | 0 (0) |
| Step 14 additions | topic without name | 5 | 5 | 5 | 0 | 0 | 0 (0) |
| Step 6 (frozen) | before summary | 5 | 5 | 5 | 0 | 0 | 2 (0) |
| Step 6 (frozen) | decision changed | 4 | 8 | 3 | 4 | 1 | 2 (0) |
| Step 6 (frozen) | edited old words | 3 | 0 | 0 | 0 | 0 | 2 (0) |
| Step 6 (frozen) | exact detail far back | 6 | 6 | 6 | 0 | 0 | 3 (2) |
| Step 6 (frozen) | failed or unfinished reply | 3 | 0 | 0 | 0 | 0 | 0 (0) |
| Step 6 (frozen) | follow up without subject | 4 | 4 | 3 | 1 | 0 | 1 (0) |
| Step 6 (frozen) | hidden best match | 4 | 1 | 1 | 0 | 0 | 1 (0) |
| Step 6 (frozen) | long dictated detail | 4 | 4 | 4 | 0 | 0 | 0 (0) |
| Step 6 (frozen) | long typed detail | 4 | 4 | 4 | 0 | 0 | 4 (1) |
| Step 6 (frozen) | more than limit | 4 | 20 | 8 | 12 | 0 | 1 (0) |
| Step 6 (frozen) | nothing relevant | 6 | 0 | 0 | 0 | 0 | 0 (0) |
| Step 6 (frozen) | roleplay and companion | 4 | 4 | 4 | 0 | 0 | 4 (0) |
| Step 6 (frozen) | single subject chat | 4 | 4 | 4 | 0 | 0 | 2 (0) |
| Step 6 (frozen) | time reference | 4 | 11 | 4 | 7 | 0 | 2 (1) |
| Step 6 (frozen) | very long exchange | 3 | 3 | 3 | 0 | 0 | 0 (0) |
| **Independent check** | **all** | **17** | **15** | **12** | **3** | **0** | **5 (2)** |
| **Step 14 additions** | **all** | **19** | **26** | **20** | **6** | **0** | **5 (1)** |
| **Step 6 (frozen)** | **all** | **62** | **74** | **49** | **24** | **1** | **24 (4)** |


**Where wrong additions come from.** Exchanges that passed two ways (meaning
and keywords, or meaning and the certain bar) were right 59 times out of 62.
Exchanges that passed one way only were right 22 times out of 53: meaning alone
11 of 24, the certain bar alone 6 of 15, keywords alone 5 of 14. Most wrong
additions are related-sounding exchanges in the same chat (another soil
question for "soil mix ratio", the Lisbon outline for "Belem Tower tickets").

### Every sample (working values, variant A titles)

"Why" for a missed exchange: *borderline* is a near miss with its numbers;
*did not pass the gate* is further away.

| Set | Question | Message | Found | Missed (why) | Wrongly added |
|---|---|---|---|---|---|
| Step 6 (frozen) | `q-garden-exact-tomatoes` | Which two tomato varieties did I decide to grow? | “For tomatoes I'm going to grow…” | — | — |
| Step 6 (frozen) | `q-garden-exact-soil` | What was the soil mix ratio we worked out? | “For filling the beds, I read c…” | — | “What should go in the bottom o…” **must-not**, “Should I start the seeds in ga…” |
| Step 6 (frozen) | `q-garden-time-last-week` | What did I change in the garden plan last week? | “I changed the trellis plan for…”, “I dropped the eggplant from be…” | “Change of plan for the basil: …” (borderline: similarity 0.57, stand-out 2.101, keywords 0.442) | “I'm changing the watering sche…” **must-not**, “Lumber has doubled in price, s…” |
| Step 6 (frozen) | `q-garden-time-march` | What did I plant in March? | “Today I planted the pepper see…”, “I planted the snap peas direct…” | — | — |
| Step 6 (frozen) | `q-garden-subject-watering` | How often should I water after transplanting? | “How often should I water the s…” | — | “Change of plan for the basil: …” |
| Step 6 (frozen) | `q-garden-subject-slugs` | What did we decide to do about the slugs? | “Slugs have already found the l…” | — | “I'm changing the watering sche…” |
| Step 6 (frozen) | `q-garden-subject-compost` | Where did we say the compost bin should go? | “Where should the compost bin g…” | — | — |
| Step 6 (frozen) | `q-garden-subject-squash` | How much room does each squash plant need? | “How far apart should the squas…” | — | — |
| Step 6 (frozen) | `q-garden-decision-cedar` | Why did we pick cedar for the beds? | “Let's go with three raised bed…” | “Lumber has doubled in price, s…” (borderline: similarity 0.515, stand-out 2.21, keywords 0.136) | “I checked lumber prices for th…”, “I want to start a vegetable ga…” |
| Step 6 (frozen) | `q-garden-decision-steel` | How tall are the galvanized steel beds going to be? | “Lumber has doubled in price, s…” | “Let's go with three raised bed…” (did not pass the gate) | — |
| Step 6 (frozen) | `q-trip-exact-flight` | What was the flight number for the outbound flight? | “I booked the outbound flight: …” | — | — |
| Step 6 (frozen) | `q-trip-exact-belem` | What time are our Belem Tower tickets? | “Got the Belem Tower tickets fo…” | — | “Can you put together a rough d…” **must-not** |
| Step 6 (frozen) | `q-trip-summary-budget` | How much did we budget per day for food? | “Clara and I agreed on a food b…” | — | — |
| Step 6 (frozen) | `q-trip-summary-transit` | What did you tell me about the transit card in Lisbon? | “How does public transport work…” | — | — |
| Step 6 (frozen) | `q-trip-summary-sintra` | What day are we going to Sintra and what time is the palace entry? | “I booked Sintra for Tuesday Ma…” | — | “Can you put together a rough d…” |
| Step 6 (frozen) | `q-trip-summary-guesthouse` | What's the name of the place we're staying in Porto? | “I booked a guesthouse in Porto…” | — | — |
| Step 6 (frozen) | `q-trip-summary-teodora` | What was the restaurant Teodora recommended? | “My friend Teodora recommended …” | — | “Clara is vegetarian. I found a…” |
| Step 6 (frozen) | `q-trip-time-last-week` | What did we plan last week? | — | “I booked the car with Atlantic…” (did not pass the gate); “For lunch in Coimbra I booked …” (did not pass the gate); “I confirmed a short boat ride …” (did not pass the gate) | — |
| Step 6 (frozen) | `q-trip-time-january` | What did we book back in January? | — | “I booked the outbound flight: …” (did not pass the gate); “I booked Sintra for Tuesday Ma…” (borderline: similarity 0.409, stand-out 2.526, keywords 0.186); “Got the Belem Tower tickets fo…” (did not pass the gate) | — |
| Step 6 (frozen) | `q-trip-decision-train` | How were we taking the Alfa Pendular train to Porto? | “For getting from Lisbon to Por…” | “Change of plan for getting nor…” (did not pass the gate) | — |
| Step 6 (frozen) | `q-trip-decision-car` | Remind me why we wanted to rent a car and stop in Coimbra? | — | “For getting from Lisbon to Por…” (did not pass the gate) · already in the request: “Change of plan for getti…” | — |
| Step 6 (frozen) | `q-trip-limit-restaurants` | Which restaurants have we picked out so far? | “We should have one seafood res…”, “Clara is vegetarian. I found a…” | “My friend Teodora recommended …” (did not pass the gate); “For Clara's birthday dinner on…” (borderline: similarity 0.396, stand-out 2.141, keywords 0.206); “For lunch in Coimbra I booked …” (did not pass the gate) | — |
| Step 6 (frozen) | `q-app-exact-minsdk` | What minimum Android version did we settle on for the app? | “What minimum Android version s…” | — | — |
| Step 6 (frozen) | `q-app-exact-table` | What did we name the database table that holds ingredients? | “How should we store the recipe…” | — | — |
| Step 6 (frozen) | `q-app-followup-coil` | Why did we go with that one over the others? | — | “Which image loading library sh…” (borderline: similarity 0.395, stand-out 2.176, keywords 0.0) | “What should go into version 1.…” |
| Step 6 (frozen) | `q-app-followup-sync` | And what happens when two phones change the same thing? | “What should we do about offlin…” | — | — |
| Step 6 (frozen) | `q-app-followup-theme` | What contrast did you say it has to meet? | “What colors should the dark th…” | — | — |
| Step 6 (frozen) | `q-app-followup-conversion` | How does the rounding work for that? | “How should the unit conversion…” | — | — |
| Step 6 (frozen) | `q-app-limit-bugs` | Which bugs have we fixed so far? | “Found a bug: editing a recipe …” | “There's a bug: the app crashes…” (borderline: similarity 0.489, stand-out 2.275, keywords 0.175); “Another bug: some photos show …” (borderline: similarity 0.503, stand-out 2.468, keywords 0.175); “Bug report from my brother: th…” (borderline: similarity 0.493, stand-out 2.328, keywords 0.175); “Bug: searching for creme brule…” (borderline: similarity 0.492, stand-out 2.319, keywords 0.175) | — |
| Step 6 (frozen) | `q-app-limit-screens` | What screens have we designed? | “Let's design the recipe list s…”, “Now the recipe detail screen. …”, “Let's design the add-recipe fo…”, “Let's design the shopping list…”, “Let's design the settings scre…” | — | “Would a home screen widget be …” |
| Step 6 (frozen) | `q-app-edited-firebase` | What did I ask about using Firebase for sign-in? | — | — | “How should we release the firs…”, “Do we need an onboarding flow?” |
| Step 6 (frozen) | `q-app-failed-pdf` | What did you say about turning a recipe into a PDF? | — | — | — |
| Step 6 (frozen) | `q-mira-typed-vet` | When is Biscuit's vet appointment? | “Okay, this week has been a lot…” | — | — |
| Step 6 (frozen) | `q-mira-typed-allergy` | Which cousin has the nut allergy and what are they allergic to? | “I'm making a list for the fami…” | — | — |
| Step 6 (frozen) | `q-mira-dictated-presentation` | When did the presentation get moved to? | “so I'm walking home right now …” | — | — |
| Step 6 (frozen) | `q-mira-dictated-library` | When is my library book due back? | “okay quick voice note while I'…” | — | — |
| Step 6 (frozen) | `q-mira-hidden-party` | When is the surprise party for Jonah? | — | — | — |
| Step 6 (frozen) | `q-mira-hidden-gift` | What gift idea did I have for Nora's birthday? | “I went back to the Saturday ma…” | — | “Nora and I drove out to the ol…” |
| Step 6 (frozen) | `q-mira-edited-landlord` | What did I say about the landlord and the broken heater? | — | — | — |
| Step 6 (frozen) | `q-mira-unfinished-puzzle` | What did you say about doing a jigsaw puzzle on the kitchen table? | — | — | — |
| Step 6 (frozen) | `q-mira-nothing-boardgame` | Can you recommend a board game for four players? | — | — | — |
| Step 6 (frozen) | `q-mira-nothing-bread` | What's a quick recipe for banana bread? | — | — | — |
| Step 6 (frozen) | `q-mira-scope-lighthouse` | What did I tell you about the lighthouse during the storm? | “Nora and I drove out to the ol…” | — | — |
| Step 6 (frozen) | `q-mira-scope-key` | Where did I find the brass key? | “Remember my grandmother's bras…” | — | — |
| Step 6 (frozen) | `q-salt-long-rescue` | In the shipwreck chapter, what was the ship called and how many sailors were saved? | “Write the full chapter of the …” | — | — |
| Step 6 (frozen) | `q-salt-long-bell` | What was the drowned bell made from, and what was it called? | “At the inn, Wren asks Hesketh …” | — | — |
| Step 6 (frozen) | `q-salt-hidden-uncle` | Is Ansel secretly Wren's uncle? | — | — | — |
| Step 6 (frozen) | `q-salt-scope-lighthouse` | What did Wren find at the top of the lighthouse in the storm? | “That night a storm hits. Wren …” | — | “Write the full chapter of the …”, “While cleaning the keeper's bo…”, “Wren decides to use the key an…”, “At the inn, Wren asks Hesketh …” |
| Step 6 (frozen) | `q-salt-scope-key` | Where did Wren find the brass key? | “While cleaning the keeper's bo…” | — | — |
| Step 6 (frozen) | `q-club-typed-cafe` | Where did the book club meeting get moved to? | “Okay, I want to get my thought…” | — | “right so I'm in the car and I …”, “I joined a book club! We meet …”, “okay voice note about the sche…” **must-not** |
| Step 6 (frozen) | `q-club-typed-letter` | Which chapter is the one where the narrator lies about the letter? | “I finished rereading the middl…” | — | “Okay, I want to get my thought…” |
| Step 6 (frozen) | `q-club-dictated-dolores` | What did I promise to bring for Dolores? | “right so I'm in the car and I …” | — | — |
| Step 6 (frozen) | `q-club-dictated-august` | Who is hosting in August? | “okay voice note about the sche…” | — | — |
| Step 6 (frozen) | `q-club-hidden-skim` | What did I say about having to skim chapter 20? | — | — | — |
| Step 6 (frozen) | `q-club-edited-mystery` | What did I suggest about reading a mystery novel set on a train? | — | — | — |
| Step 6 (frozen) | `q-club-failed-villanelle` | What was the discussion question about the villanelle? | — | — | — |
| Step 6 (frozen) | `q-club-nothing-bike` | How do I fix a squeaky bike brake? | — | — | — |
| Step 6 (frozen) | `q-club-nothing-neck` | What's a good stretch for a stiff neck? | — | — | — |
| Step 6 (frozen) | `q-rowan-long-week7` | What was the long run in week 7 of my plan? | “Can you write out the full twe…” | — | — |
| Step 6 (frozen) | `q-rowan-nothing-paint` | Can you help me pick a paint colour for the hallway? | — | — | — |
| Step 6 (frozen) | `q-rowan-nothing-goldfish` | What's a good name for a goldfish? | — | — | — |
| Step 6 (frozen) | `q-home-limit-leaks` | Which leaks have we fixed around the house? | — | “There's a leak under the kitch…” (did not pass the gate); “The bathtub faucet has a slow …” (did not pass the gate); “After heavy rain there's a lea…” (did not pass the gate); “I found a leak behind the wash…” (did not pass the gate); “The outdoor spigot has a leak …” (did not pass the gate) | — |
| Step 14 additions | `q-kitchen-correction` | What countertop did we settle on for the island? | “Actually, forget quartz. We're…” | “Tell me everything about quart…” (did not pass the gate) | — |
| Step 14 additions | `q-kitchen-correction-2` | Are we still doing quartz countertops? | “Tell me everything about quart…”, “Actually, forget quartz. We're…” | — | — |
| Step 14 additions | `q-kitchen-permit` | Did the contractor ever get back to me about the electrical permit? | “I emailed the contractor about…”, “The contractor finally replied…” | — | — |
| Step 14 additions | `q-kitchen-permit-2` | When was I waiting on the permit reply, and what did it say in the end? | “I emailed the contractor about…”, “The contractor finally replied…” | — | — |
| Step 14 additions | `q-kitchen-repeat` | What did I need to measure above the sink? | “Remind me to measure the windo…” | — | “Actually, forget quartz. We're…”, “Tell me everything about quart…” |
| Step 14 additions | `q-kitchen-unrelated` | Can you recommend a good audiobook for a long drive? | — | — | — |
| Step 14 additions | `q-gl-ivy-pottery` | What did Ivy say about the pottery class? | “Hi Juniper. I signed up for a …” | “The wheel. The teacher said we…” (did not pass the gate) | — |
| Step 14 additions | `q-gl-mara-sailing` | What did Mara tell Juniper about the sailing trip? | — | “Different topic. I'm going on …” (did not pass the gate); “The sailing trip is set: we le…” (did not pass the gate) | — |
| Step 14 additions | `q-gl-glaze` | Which glaze did I choose for the vase? | “I picked the glaze for my vase…” | — | — |
| Step 14 additions | `q-gl-watch` | What time is my night watch on the boat? | “My cousin wants me to take the…” | — | — |
| Step 14 additions | `q-sol-current-name` | What did Solace suggest for my mornings? | “Sol, I want a calmer morning r…” | “The phone in the kitchen trick…” (did not pass the gate) | — |
| Step 14 additions | `q-sol-old-name` | What did Sol say about the evening wind-down? | “Can you suggest an evening win…” | — | — |
| Step 14 additions | `q-sol-notebook` | What kind of notebook did I buy? | “I bought a notebook for the jo…” | — | — |
| Step 14 additions | `q-finn-ford` | Where did Finn want to cross the river? | “Finn shoulders his pack and st…” | — | “Once ashore, Finn the ranger m…” **must-not** |
| Step 14 additions | `q-finn-boatman-fare` | How much did Finn the boatman charge? | “Finn shoulders his pack and st…” | “Finn the ranger counts out two…” (did not pass the gate) | — |
| Step 14 additions | `q-finn-stone` | How did we mark the smugglers' path? | “Once ashore, Finn the ranger m…” | — | — |
| Step 14 additions | `q-rook-promise` | What did Captain Rook promise the harbor master? | “Rook raises a hand. 'I promise…” | — | “Captain Rook strides up to the…”, “At dawn Rook opens the hatch f…” |
| Step 14 additions | `q-rook-ivy-promise` | What did Ivy promise about the cargo in the hold? | “Rook raises a hand. 'I promise…” | — | — |
| Step 14 additions | `q-rook-shoals` | What did the cartographer say about the northern shoals? | “That night Rook meets the cart…” | — | — |
| Independent check | `h-bakery-menu` | What four things did I put on the menu? | “Menu decision: sourdough loave…” | — | — |
| Independent check | `h-bakery-starter` | wat did u say bout feedin the starter if i only bake fridays | “How often do I feed the sourdo…” | — | — |
| Independent check | `h-bakery-fee-dictated` | okay so I'm standing in the parking lot trying to remember the numbers the market manager  | “The market manager says the st…” | — | — |
| Independent check | `h-bakery-oven` | Are we still buying the deck oven from the cafe? | “I found a secondhand deck oven…”, “The deck oven fell through. Th…” | — | — |
| Independent check | `h-bakery-crackers` | What labels do the crackers need? | “What labels do I legally need …” | “Change of plan: I'm dropping t…” (borderline: similarity 0.47, stand-out 2.222, keywords 0.303) | — |
| Independent check | `h-bakery-nothing` | What's the capital of Mongolia? | — | — | — |
| Independent check | `h-bakery-nothing-2` | Can you help me write a birthday poem for my dad? | — | — | — |
| Independent check | `h-bakery-common-word` | What about the first one? | — | — | “my sister wants to help on the…” **must-not**, “I'm opening a tiny bakery stal…” |
| Independent check | `h-bakery-price` | how much r the cardamom buns | “What should I charge for the c…” | — | “Menu decision: sourdough loave…” **must-not** |
| Independent check | `h-pip-oak-reel` | What reel did Oak learn first? | “Pip, I've started learning the…” | — | — |
| Independent check | `h-pip-lark-bats` | What did Lark build for the bats? | — | “New hobby too: I'm building a …” (borderline: similarity 0.54, stand-out 2.122, keywords 0.493) | — |
| Independent check | `h-pip-old-name` | What did Pip say about the squeaky bow? | — | “My bow keeps squeaking on the …” (did not pass the gate) | — |
| Independent check | `h-pip-new-name` | Where did Piper tell me to mount the bat box? | “New hobby too: I'm building a …” | — | “The bat box is up on the gable…” |
| Independent check | `h-pip-topic` | Which bats did I see in the garden? | “I saw two pipistrelles flying …” | — | — |
| Independent check | `h-ash-guide` | Who did Ash say could guide us over the pass? | “Ash unrolls her half-finished …” | — | — |
| Independent check | `h-ash-marker` | What was carved on the stone marker? | “On the second day, Ash finds a…” | — | “Ash the cartographer agrees, a…” |
| Independent check | `h-ash-nothing` | What's a good recipe for banana bread? | — | — | — |

### Follow-ups by context-assistance mode (chats without saved memories)

The previous message is sent first and what recall finds for it (at most 3
exchanges) is treated as delivered. Code-based then decides whether to carry
them (G36, Owner 2026-11-25): only when the message found nothing of its own,
the saved-memory subject did not change, it is at most 25 words, and at least
half of its own words (follow-up words such as "that one" left out) are in the
carried exchange. API AI uses a hand-written stand-in for the helper's query;
no model wrote it and no paid call was made.

| Follow-up | Kind | Message | Off | Code-based | API AI (hand-written query) |
|---|---|---|---|---|---|
| `fu-app-followup-coil` | genuine | Why did we go with that one over the others? | found 0/1, wrong 1 | found 0/1, wrong 1, carried 0 (OWN_RESULTS_FOUND) | found 1/1, wrong 1 |
| `fu-app-followup-sync` | genuine | And what happens when two phones change the same thing? | found 1/1, wrong 0 | found 1/1, wrong 0, carried 0 (OWN_RESULTS_FOUND) | found 1/1, wrong 0 |
| `fu-app-followup-theme` | genuine | What contrast did you say it has to meet? | found 1/1, wrong 0 | found 1/1, wrong 0, carried 0 (OWN_RESULTS_FOUND) | found 1/1, wrong 0 |
| `fu-app-followup-conversion` | genuine | How does the rounding work for that? | found 1/1, wrong 0 | found 1/1, wrong 0, carried 0 (OWN_RESULTS_FOUND) | found 1/1, wrong 0 |
| `fu-garden-tomato-why` | genuine | Why those two? | found 0/1, wrong 0 | found 0/1, wrong 0, carried 0 (CHECKED) | found 1/1, wrong 0 |
| `fu-garden-joke` | independent | Tell me a joke about penguins. | nothing expected, wrong 0 | nothing expected, wrong 0, carried 0 (CHECKED) | nothing expected, wrong 0 |
| `fu-app-podcast` | independent | Can you recommend a good podcast for my commute? | nothing expected, wrong 0 | nothing expected, wrong 0, carried 0 (CHECKED) | nothing expected, wrong 0 |
| `fu-app-calendar` | independent | How do I sync my calendar with my car? | nothing expected, wrong 1 (1 must-not) | nothing expected, wrong 1 (1 must-not), carried 0 (OWN_RESULTS_FOUND) | nothing expected, wrong 1 (1 must-not) |
| `fu-trip-haiku` | independent | Write me a short haiku about rain. | nothing expected, wrong 0 | nothing expected, wrong 0, carried 0 (CHECKED) | nothing expected, wrong 0 |
| `fu-mira-thanks` | independent | Okay, what's a good stretch for a stiff neck? | nothing expected, wrong 0 | nothing expected, wrong 0, carried 0 (CHECKED) | nothing expected, wrong 0 |
| `fu-kitchen-island-more` | genuine | How often does it need oiling? | found 1/1, wrong 1 | found 1/1, wrong 1, carried 0 (OWN_RESULTS_FOUND) | found 1/1, wrong 1 |
| independent check: `hf-bakery-buns-why` | genuine | and why that much? | found 0/1, wrong 0 | found 1/1, wrong 1, carried 2 (CHECKED) | found 1/1, wrong 0 |
| independent check: `hf-bakery-reader` | genuine | Does it work offline? | found 1/1, wrong 0 | found 1/1, wrong 0, carried 0 (OWN_RESULTS_FOUND) | found 1/1, wrong 1 |
| independent check: `hf-pip-reel-tempo` | genuine | What speed should I practise it at? | found 1/1, wrong 0 | found 1/1, wrong 0, carried 0 (OWN_RESULTS_FOUND) | found 1/1, wrong 0 |
| independent check: `hf-bakery-weather` | independent | What's the weather going to be like tomorrow? | nothing expected, wrong 0 | nothing expected, wrong 0, carried 0 (CHECKED) | nothing expected, wrong 0 |
| independent check: `hf-bakery-film` | independent | Recommend me a film for tonight. | nothing expected, wrong 0 | nothing expected, wrong 0, carried 0 (CHECKED) | nothing expected, wrong 0 |
| independent check: `hf-pip-tax` | independent | How do I file my taxes online? | nothing expected, wrong 0 | nothing expected, wrong 0, carried 0 (CHECKED) | nothing expected, wrong 0 |
| independent check: `hf-ash-joke` | independent | Tell me something funny. | nothing expected, wrong 0 | nothing expected, wrong 0, carried 0 (CHECKED) | nothing expected, wrong 1 |

- **Unrelated requests never received a carried exchange** (Code-based, 9 of
  9). The one wrong result for "How do I sync my calendar with my car?" is the
  message's own search matching the syncing exchange on "sync", not
  continuity.
- **Code-based carried correctly** for "and why that much?" (independent
  check) but also carried the second, wrong exchange delivered with the
  previous message: carrying passes on whatever was delivered.
- **Code-based missed two genuine follow-ups.** "Why did we go with that one
  over the others?": a weak own match (wrong) stopped the carry, because new
  results always take priority. "Why those two?": "two" is not in the tomato
  exchange, so it counted as naming something else.
- API AI found every genuine follow-up (9 of 9) with its hand-written queries;
  the real helper's queries are not measured here.

### Speaker titles (Owner, 2026-11-23)

| Questions | Variant | Expected | Found | Missed | Wrongly added (must-not) |
|---|---|---:|---:|---:|---:|
| Name an identity (Step 14 additions) | A: Human/Companion | 12 | 7 | 5 | 3 (1) |
| Name an identity (Step 14 additions) | B: with recorded names | 12 | 11 | 1 | 6 (2) |
| Name an identity (Step 14 additions) | C: identity-ID filter | 12 | 7 | 5 | 3 (1) |
| Name an identity (Step 14 additions) | B and C | 12 | 11 | 1 | 6 (2) |
| No name (Step 14 additions) | A: Human/Companion | 5 | 5 | 0 | 0 (0) |
| No name (Step 14 additions) | B: with recorded names | 5 | 5 | 0 | 0 (0) |
| No name (Step 14 additions) | C: identity-ID filter | 5 | 5 | 0 | 0 (0) |
| No name (Step 14 additions) | B and C | 5 | 5 | 0 | 0 (0) |
| Name an identity (independent check) | A: Human/Companion | 5 | 3 | 2 | 1 (0) |
| Name an identity (independent check) | B: with recorded names | 5 | 4 | 1 | 4 (1) |
| Name an identity (independent check) | C: identity-ID filter | 5 | 3 | 2 | 1 (0) |
| Name an identity (independent check) | B and C | 5 | 4 | 1 | 4 (1) |
| No name (independent check) | A: Human/Companion | 2 | 2 | 0 | 1 (0) |
| No name (independent check) | B: with recorded names | 2 | 2 | 0 | 0 (0) |
| No name (independent check) | C: identity-ID filter | 2 | 2 | 0 | 1 (0) |
| No name (independent check) | B and C | 2 | 2 | 0 | 0 (0) |

- **B (recorded names in the title)** found more of the exchanges a question
  names (7 → 11 of 12; 3 → 4 of 5 on the independent check) and also added
  more wrong ones (3 → 6; 1 → 4), mostly other exchanges of the same
  identity.
- **C (identity-ID filter)** changed nothing. It can only remove candidates,
  and the missed named exchanges failed the relevance gate itself; in the test
  chats every wrongly added exchange was recorded under the named identity
  anyway.
- **No-name questions** were unaffected by names (5 of 5 and 2 of 2 found in
  every variant; B removed one wrong addition on the independent check).
- **Renamed identity:** "What did Pip say…" (the companion's old name) is not
  resolved by C, which knows identities by their current name; B's recorded
  title "Companion: Pip" is what such a question can match.
- The Step 6 chats have no recorded names or identities, so their results are
  the same in every variant; only the new chats carry synthetic test
  annotations standing in for stored names and IDs.
- **No policy is chosen.** Human/Companion stays the app's behavior; B's gain
  in found exchanges comes with more wrong ones, which the owner ranks worse.

### Decisions changed later (G.6 stage 9 not built)

Both the earlier and the later exchange were found in **1 of 6** questions
(Step 6: 0 of 4; independent check: 1 of 2). The question's own words usually
match only one of the two: "Why did we pick cedar?" finds the cedar decision
but not the later switch to steel; "Remind me why we wanted to rent a car" found
neither. The Step 14 correction case ("Are we still doing quartz?") found the
later short correction both times despite the earlier exchange's many matching
passages. This is the measurement that decides whether stage 9 is built.

### Ranking: weighted blend and rank fusion

Taking each question's best 3, the blend and rank fusion keep the same
exchanges (78 correct, 30 wrong in the best 3 over all questions). Rank fusion
gives no reason to replace the blend.

### Time (not phone timings)

- **Desktop development machine:** recall's own search, with the message's
  vector already read, takes a median of 1 ms and at most 24 ms per question.
- **Emulator:** about 240 ms to read a typed message and 3–16 ms to search
  (above).
- **Phone:** not measured. Step 15 measures the phone, including the
  whole-message read and queue waits.

### Android's runtime (CI emulator)

`RecallEvaluationInstrumentedTest` ran the same evaluation through the real
encrypted index, the real Step 8–9 indexing and the real model with Android
ONNX Runtime 1.20 (x86_64 emulator, Android Checks run 45788618502, commit
`53ee2725`) for three chats: the independent-check bakery and Ash chats and the
two-Finns roleplay chat. That is 56 passages and every question and follow-up
of those chats under all four title variants and all three modes.

- **Every decision was the same as on the desktop runtime: 75 of 75**
  (questions × variants and follow-ups × modes), with the same exchanges in
  the same order. The largest difference in a reported similarity was 0.018.
- **Emulator time:** a passage vector took about 400 ms (56 in 22.6 s); a
  question's whole-message read 233–336 ms (through the live queue, nothing
  else waiting); recall's own search 3–16 ms. Every question finished within
  both the typed (1,500 ms) and hands-free (750 ms) allowances. Emulator
  timings are not phone timings.
- **Why only three chats.** Two earlier runs of larger sets (all chats, then
  six chats of about 7,400 words) did not finish within the class's 20-minute
  limit. The likeliest cause is the cost of reading long 256-token passages on
  the emulator (the six chats include a 1,500-word exchange); it was not
  measured directly. No hang was seen in any desktop or unit-test run.


### Remaining weaknesses

- **Wrong additions remain**: 29 on the chosen sets and 5 on the independent
  check, mostly exchanges that pass one way only and sound related. A
  re-ranker or stricter single-way bars are Step 15 questions; the certain-bar
  trade-off is above.
- **Time questions with no subject** ("What did we plan last week?") find
  nothing: a date range only favours passages that pass on their own (G.6), so
  4 of 11 expected exchanges were found.
- **"More than the limit" questions** found 8 of 20: generic questions ("Which
  bugs have we fixed?") reach only the closest few.
- **Changed decisions** are rarely both found (1 of 6).
- **Word forms:** "squeaky" does not match "squeaking" (the keyword stemmer
  does not reduce "-y"), and dictated misspellings rely on meaning alone.
- **Code-based continuity** misses a genuine follow-up when the message's own
  search finds something weak, and passes on a wrong exchange that was
  delivered with the previous message.
- **The samples are small** (98 questions, 18 follow-up scenarios) and partly
  written by the same session that chose two values.
