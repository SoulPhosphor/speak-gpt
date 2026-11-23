#!/usr/bin/env bash
# Android Checks: emulator start, health checks, bounded recovery,
# diagnostics, and the per-class instrumentation suite.
#
#   emulator-suite.sh start
#   emulator-suite.sh run-suite CLASS...
#   emulator-suite.sh diagnostics LABEL
#
# Outcome of each class attempt (suite-summary.tsv):
#   passed          the runner reported "OK (".
#   test-failure    the runner reported failing tests or an app crash. Never retried.
#   timeout         the class exceeded CLASS_TIMEOUT_SECONDS. Never retried.
#   incomplete      the runner did not provably complete (see completion_problem:
#                   adb exit code, final result code, OK count vs started
#                   tests) although the emulator still responds. Never retried.
#   infrastructure  not provably complete and the emulator stopped responding. After a
#                   successful recovery the class runs once more; both attempts
#                   stay in the summary.
#   setup-failure   the class could not be prepared (for example the network
#                   could not be switched off). Never retried.
set -uo pipefail

RESULTS_DIR="${RESULTS_DIR:-${RUNNER_TEMP:-/tmp}/recovery-results}"
EMULATOR_LOG="${EMULATOR_LOG:-${RUNNER_TEMP:-/tmp}/recovery-emulator.log}"
EMULATOR_PID_FILE="${EMULATOR_PID_FILE:-${RUNNER_TEMP:-/tmp}/recovery-emulator.pid}"
AVD_NAME="${AVD_NAME:-recovery-matrix}"
APP_PACKAGE="${APP_PACKAGE:-com.soulphosphor.phosphorshines}"
TEST_RUNNER="${TEST_RUNNER:-com.soulphosphor.phosphorshines.test/androidx.test.runner.AndroidJUnitRunner}"
APP_APK="${APP_APK:-app/build/outputs/apk/debug/app-debug.apk}"
TEST_APK="${TEST_APK:-app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk}"
CLASS_TIMEOUT_SECONDS="${CLASS_TIMEOUT_SECONDS:-1200}"
MAX_RECOVERIES="${MAX_RECOVERIES:-2}"
MAX_EMULATOR_RESTARTS="${MAX_EMULATOR_RESTARTS:-1}"
BOOT_WAIT_SECONDS="${BOOT_WAIT_SECONDS:-450}"
STEP2_CLASS="Step2RecallInstrumentedTest"

DIAG_DIR="$RESULTS_DIR/diagnostics"
SUMMARY_TSV="$RESULTS_DIR/suite-summary.tsv"
EVENTS="$RESULTS_DIR/recovery-events.txt"
mkdir -p "$RESULTS_DIR" "$DIAG_DIR"

now() { date -u +%Y-%m-%dT%H:%M:%SZ; }
event() { echo "$(now) $*" | tee -a "$EVENTS" >&2; }
annotate() { echo "::$1::$2"; }

emulator_pid() { cat "$EMULATOR_PID_FILE" 2>/dev/null || true; }
emulator_process_alive() {
  local pid; pid="$(emulator_pid)"
  [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

# Bounded adb call: never hangs the suite on a wedged connection.
adb_t() { local limit="$1"; shift; timeout "$limit" adb "$@"; }

# Alive, booted, and answering a shell command.
device_healthy() {
  [ "$(adb_t 15 get-state 2>/dev/null | tr -d '\r')" = "device" ] || return 1
  [ "$(adb_t 15 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] || return 1
  [ "$(adb_t 15 shell echo ready 2>/dev/null | tr -d '\r')" = "ready" ]
}

wait_for_boot() {
  local deadline=$((SECONDS + BOOT_WAIT_SECONDS))
  while [ "$SECONDS" -lt "$deadline" ]; do
    if ! emulator_process_alive; then
      event "emulator process exited while booting"
      return 1
    fi
    if device_healthy; then return 0; fi
    sleep 5
  done
  event "emulator did not finish booting within ${BOOT_WAIT_SECONDS}s"
  return 1
}

launch_emulator() {
  export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-${RUNNER_TEMP:-/tmp}/android-avd}"
  # The first launch keeps the established log name; a restart appends.
  echo "=== emulator launch $(now) ===" >> "$EMULATOR_LOG"
  nohup "$ANDROID_HOME/emulator/emulator" "@$AVD_NAME" \
    -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect \
    -camera-back none -camera-front none -wipe-data \
    >> "$EMULATOR_LOG" 2>&1 &
  echo $! > "$EMULATOR_PID_FILE"
}

configure_device() {
  adb_t 30 shell settings put global window_animation_scale 0
  adb_t 30 shell settings put global transition_animation_scale 0
  adb_t 30 shell settings put global animator_duration_scale 0
}

install_apks() {
  adb_t 300 install -t "$APP_APK" && adb_t 300 install -t "$TEST_APK"
}

cmd_start() {
  set -e
  export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-${RUNNER_TEMP:-/tmp}/android-avd}"
  mkdir -p "$ANDROID_AVD_HOME"
  echo no | avdmanager create avd \
    --force \
    --name "$AVD_NAME" \
    --package "system-images;android-35;google_apis;x86_64" \
    --device pixel_6 \
    --path "$ANDROID_AVD_HOME/$AVD_NAME.avd"
  sudo chmod 666 /dev/kvm
  set +e
  launch_emulator
  if ! wait_for_boot; then
    tail -n 200 "$EMULATOR_LOG"
    collect_diagnostics "emulator-start-failed"
    exit 1
  fi
  configure_device || { collect_diagnostics "emulator-configure-failed"; exit 1; }
  event "emulator booted (pid $(emulator_pid))"
}

# Small, bounded evidence for one moment. Every command is optional.
collect_diagnostics() {
  local label="$1"
  local dir; dir="$DIAG_DIR/$(date -u +%H%M%S)-$label"
  mkdir -p "$dir"
  {
    echo "label: $label"
    echo "time: $(now)"
    echo "emulator pid: $(emulator_pid)"
    if emulator_process_alive; then echo "emulator process: running"; else echo "emulator process: not running"; fi
    echo "--- adb devices -l"; adb_t 15 devices -l 2>&1
    echo "--- adb get-state"; adb_t 15 get-state 2>&1
    echo "--- sys.boot_completed"; adb_t 15 shell getprop sys.boot_completed 2>&1
    echo "--- host uptime/load"; uptime 2>&1
    echo "--- host memory"; free -m 2>&1
    echo "--- host disk"; df -h / "${RUNNER_TEMP:-/tmp}" 2>&1
    echo "--- emulator processes"; ps -o pid,stat,etime,rss,pcpu,args -C qemu-system-x86_64-headless 2>&1 | cut -c1-200
  } > "$dir/state.txt" 2>&1
  tail -n 120 "$EMULATOR_LOG" > "$dir/emulator-log-tail.txt" 2>/dev/null || true
  adb_t 20 shell cat /proc/meminfo > "$dir/device-meminfo.txt" 2>&1 || true
  adb_t 20 shell df -h /data > "$dir/device-disk.txt" 2>&1 || true
  adb_t 30 logcat -d -b crash -v threadtime > "$dir/device-crash-log.txt" 2>&1 || true
  adb_t 30 logcat -d -v threadtime -t 400 > "$dir/device-logcat-tail.txt" 2>&1 || true
  echo "$dir"
}

recoveries=0
emulator_restarts=0

# Bounded recovery after a confirmed loss of the emulator. Returns 0 when the
# emulator is healthy again; the caller decides whether a rerun is safe.
recover_emulator() {
  local reason="$1"
  if [ "$recoveries" -ge "$MAX_RECOVERIES" ]; then
    event "recovery limit ($MAX_RECOVERIES) reached; not recovering ($reason)"
    return 1
  fi
  recoveries=$((recoveries + 1))
  event "recovery $recoveries: $reason"
  if emulator_process_alive; then
    adb_t 30 reconnect offline >/dev/null 2>&1 || true
    adb_t 30 reconnect >/dev/null 2>&1 || true
    if adb_t 120 wait-for-device >/dev/null 2>&1 && wait_for_boot; then
      event "recovery $recoveries: adb reconnected"
      return 0
    fi
  fi
  if [ "$emulator_restarts" -ge "$MAX_EMULATOR_RESTARTS" ]; then
    event "recovery $recoveries: restart limit ($MAX_EMULATOR_RESTARTS) reached"
    return 1
  fi
  emulator_restarts=$((emulator_restarts + 1))
  event "recovery $recoveries: restarting emulator (restart $emulator_restarts)"
  local pid; pid="$(emulator_pid)"
  [ -n "$pid" ] && kill "$pid" 2>/dev/null
  sleep 5
  [ -n "$pid" ] && kill -9 "$pid" 2>/dev/null
  adb kill-server >/dev/null 2>&1 || true
  launch_emulator
  if ! wait_for_boot; then
    event "recovery $recoveries: restarted emulator did not boot"
    return 1
  fi
  if ! configure_device || ! install_apks; then
    event "recovery $recoveries: could not reinstall the test APKs"
    return 1
  fi
  # A restart wipes the device, including the prepared embedding model.
  step2_model_present=0
  event "recovery $recoveries: emulator restarted and APKs reinstalled"
  return 0
}

step2_model_present=1

# Why a run did not provably complete, or nothing when it did. "Complete"
# means all of: adb exited 0; the runner's final INSTRUMENTATION_CODE is -1
# (RESULT_OK) and is the last result code printed; the summary says
# "OK (N tests)"; no test reported an error (-1) or failure (-2); and N plus
# ignored tests (-3) equals the number of tests that started. A bare "OK ("
# is never enough.
completion_problem() {
  local output="$1" exit_code="$2" text starts ignored bad ok_n codes
  [ "$exit_code" -eq 0 ] || { echo "adb exited $exit_code"; return; }
  text="$(tr -d '\r' < "$output" 2>/dev/null)"
  codes="$(grep -E '^INSTRUMENTATION_CODE: ' <<<"$text")"
  [ -n "$codes" ] || { echo "no INSTRUMENTATION_CODE (runner did not finish)"; return; }
  [ "$(tail -n 1 <<<"$codes")" = "INSTRUMENTATION_CODE: -1" ] || { echo "final $(tail -n 1 <<<"$codes")"; return; }
  ok_n="$(grep -oE '^OK \([0-9]+ tests?\)' <<<"$text" | tail -n 1 | grep -oE '[0-9]+')"
  [ -n "$ok_n" ] || { echo "no OK summary"; return; }
  starts="$(grep -c '^INSTRUMENTATION_STATUS_CODE: 1$' <<<"$text")"
  ignored="$(grep -c '^INSTRUMENTATION_STATUS_CODE: -3$' <<<"$text")"
  bad="$(grep -cE '^INSTRUMENTATION_STATUS_CODE: -(1|2)$' <<<"$text")"
  [ "$bad" -eq 0 ] || { echo "$bad test(s) reported an error or failure"; return; }
  [ "$starts" -gt 0 ] || { echo "no test started"; return; }
  [ $((ok_n + ignored)) -eq "$starts" ] || { echo "OK ($ok_n) + ignored ($ignored) != started ($starts)"; return; }
}

# Prints the outcome; COMPLETION_NOTE explains anything short of "passed".
COMPLETION_NOTE=""
classify() {
  local output="$1" exit_code="$2"
  COMPLETION_NOTE=""
  if [ "$exit_code" -eq 124 ] || [ "$exit_code" -eq 137 ]; then echo timeout; return; fi
  if tr -d '\r' < "$output" 2>/dev/null | grep -Eq '^FAILURES!!!|^INSTRUMENTATION_STATUS_CODE: -(1|2)$|shortMsg=Process crashed|INSTRUMENTATION_FAILED'; then
    echo test-failure; return
  fi
  COMPLETION_NOTE="$(completion_problem "$output" "$exit_code")"
  if [ -z "$COMPLETION_NOTE" ]; then echo passed; return; fi
  # Not provably complete. Only a lost emulator is infrastructure (eligible
  # for one recovered rerun); otherwise the class fails as incomplete.
  if device_healthy; then echo incomplete; else echo infrastructure; fi
}

last_test_line() {
  grep -F "INSTRUMENTATION_STATUS: test=" "$1" 2>/dev/null | tail -n 1 | sed 's/.*test=//'
}

# Clearing the log only keeps each class's logcat file separate. A failure is
# recorded but never fails a test (this caused the Nov 22 2026 exit 255s).
clear_logcat() {
  local attempt
  for attempt in 1 2 3; do
    if adb_t 30 logcat -c >/dev/null 2>&1; then
      [ "$attempt" -gt 1 ] && event "adb logcat -c succeeded on attempt $attempt"
      return 0
    fi
    sleep 2
  done
  return 1
}

prepare_step2() {
  wifi_before=$(adb_t 30 shell settings get global wifi_on | tr -d '\r')
  data_before=$(adb_t 30 shell settings get global mobile_data | tr -d '\r')
  adb_t 30 shell svc wifi disable && adb_t 30 shell svc data disable
}

finish_step2() {
  if [ "${wifi_before:-}" = 1 ]; then adb_t 30 shell svc wifi enable || event "could not re-enable wifi"; fi
  if [ "${data_before:-}" = 1 ]; then adb_t 30 shell svc data enable || event "could not re-enable mobile data"; fi
}

# Only once Step2 will not run again: an infrastructure retry needs the model.
remove_step2_model() {
  adb_t 60 shell run-as "$APP_PACKAGE" rm -rf files/step2-model || event "could not remove the step2 model"
}

pull_step2_evidence() {
  local base="/sdcard/Android/data/$APP_PACKAGE/files" folder
  adb_t 60 pull "$base/step2-match.png" "$RESULTS_DIR/step2-match.png" >/dev/null 2>&1 || true
  for folder in recall_test_set recall_independent_set; do
    adb_t 60 pull "$base/step2-$folder-parity.json" "$RESULTS_DIR/step2-$folder-parity.json" >/dev/null 2>&1 || true
  done
  for folder in recall_carryover_set recall_carryover_holdout; do
    adb_t 60 pull "$base/step2-$folder-similarities.json" "$RESULTS_DIR/step2-$folder-similarities.json" >/dev/null 2>&1 || true
  done
}

# One attempt at one class. Sets OUTCOME. Runs in the current shell so the
# instrumentation output and annotations reach the job log directly.
OUTCOME=""
run_class_attempt() {
  local class_name="$1" attempt="$2"
  local result_name="${class_name##*.}"
  local suffix=""; [ "$attempt" -gt 1 ] && suffix="-attempt$attempt"
  local output="$RESULTS_DIR/$result_name$suffix.txt"
  local start=$SECONDS exit_code
  OUTCOME=""

  if ! device_healthy; then
    collect_diagnostics "$result_name-before$suffix" >/dev/null
    OUTCOME="infrastructure-before"
    return
  fi
  if ! clear_logcat; then
    if device_healthy; then
      event "$result_name: logcat could not be cleared; its logcat file may include earlier lines"
      annotate warning "$result_name: adb logcat -c failed three times; continued (logcat file may include earlier lines)"
    else
      collect_diagnostics "$result_name-logcat-clear$suffix" >/dev/null
      OUTCOME="infrastructure-before"
      return
    fi
  fi
  if [ "$result_name" = "$STEP2_CLASS" ]; then
    if [ "$step2_model_present" -ne 1 ]; then
      event "$result_name: the embedding model is not on the device (emulator was restarted)"
      printf '%s\t%s\tsetup-failure\t0\t-\t%s\n' "$class_name" "$attempt" "model missing after emulator restart" >> "$SUMMARY_TSV"
      OUTCOME="setup-failure"
      return
    fi
    if ! prepare_step2; then
      collect_diagnostics "$result_name-network-off$suffix" >/dev/null
      printf '%s\t%s\tsetup-failure\t%s\t-\t%s\n' "$class_name" "$attempt" "$((SECONDS - start))" "could not switch the network off" >> "$SUMMARY_TSV"
      finish_step2
      OUTCOME="setup-failure"
      return
    fi
  fi

  timeout --kill-after=30 "$CLASS_TIMEOUT_SECONDS" \
    adb shell am instrument -w -r -e class "$class_name" "$TEST_RUNNER" \
    > "$output" 2>&1
  exit_code=$?
  cat "$output"

  if [ "$result_name" = "$STEP2_CLASS" ]; then finish_step2; fi
  adb_t 120 logcat -d -v threadtime > "$RESULTS_DIR/$result_name$suffix-logcat.txt" 2>&1 \
    || event "$result_name: could not save its logcat"
  if [ "$result_name" = "$STEP2_CLASS" ]; then pull_step2_evidence; fi

  # classify runs in this shell (not a subshell) so COMPLETION_NOTE survives.
  local outcome
  classify "$output" "$exit_code" > "$RESULTS_DIR/.outcome"
  outcome="$(cat "$RESULTS_DIR/.outcome")"
  local last; last="$(last_test_line "$output")"
  local note="last test: ${last:-none}"
  [ -n "$COMPLETION_NOTE" ] && note="$note; not complete: $COMPLETION_NOTE"
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$class_name" "$attempt" "$outcome" "$((SECONDS - start))" "$exit_code" "$note" >> "$SUMMARY_TSV"
  if [ "$outcome" != "passed" ]; then
    [ -n "$COMPLETION_NOTE" ] && event "$result_name attempt $attempt: $outcome ($COMPLETION_NOTE)"
    collect_diagnostics "$result_name-$outcome$suffix" >/dev/null
  fi
  OUTCOME="$outcome"
}

cmd_run_suite() {
  printf 'class\tattempt\toutcome\tseconds\tinstrument_exit\tnote\n' > "$SUMMARY_TSV"
  local failed=0 class_name outcome attempt
  for class_name in "$@"; do
    attempt=1
    while :; do
      run_class_attempt "$class_name" "$attempt"
      outcome="$OUTCOME"
      case "$outcome" in
        passed) break ;;
        infrastructure|infrastructure-before)
          if [ "$outcome" = "infrastructure-before" ]; then
            printf '%s\t%s\tinfrastructure\t0\t-\t%s\n' "$class_name" "$attempt" "emulator not responding before the class started" >> "$SUMMARY_TSV"
          fi
          annotate warning "${class_name##*.} attempt $attempt: the emulator stopped responding (infrastructure)"
          if [ "$attempt" -eq 1 ] && recover_emulator "${class_name##*.} attempt $attempt"; then
            attempt=2
            continue
          fi
          annotate error "${class_name##*.}: emulator could not be recovered; the class did not run to completion"
          failed=1
          break ;;
        *)
          annotate error "${class_name##*.}: $outcome (not retried)"
          failed=1
          break ;;
      esac
    done
    if [ "${class_name##*.}" = "$STEP2_CLASS" ] && [ "$step2_model_present" -eq 1 ]; then
      remove_step2_model
    fi
  done
  write_step_summary
  return "$failed"
}

write_step_summary() {
  [ -n "${GITHUB_STEP_SUMMARY:-}" ] || return 0
  {
    echo "## Instrumentation suite"
    echo
    echo "| Class | Attempt | Outcome | Seconds | Note |"
    echo "|---|---|---|---|---|"
    tail -n +2 "$SUMMARY_TSV" | while IFS=$'\t' read -r cls att out secs _ note; do
      echo "| ${cls##*.} | $att | $out | $secs | $note |"
    done
    echo
    echo "Recoveries: $recoveries (emulator restarts: $emulator_restarts)."
    if [ -s "$EVENTS" ]; then
      echo
      echo "Events:"
      echo '```'
      cat "$EVENTS"
      echo '```'
    fi
  } >> "$GITHUB_STEP_SUMMARY"
}

MODE="${1:-}"
# Evidence even when the suite stops unexpectedly.
trap 'status=$?; if [ "$status" -ne 0 ] && [ "$MODE" = "run-suite" ]; then collect_diagnostics "suite-exit-$status" >/dev/null; fi' EXIT

case "$MODE" in
  start) shift; cmd_start "$@" ;;
  run-suite) shift; cmd_run_suite "$@" ;;
  diagnostics) shift; collect_diagnostics "${1:-manual}" ;;
  # Offline check of the completion rule against a saved runner output.
  check-output) shift; completion_problem "$1" "${2:-0}" ;;
  *) echo "usage: $0 start | run-suite CLASS... | diagnostics LABEL | check-output FILE [ADB_EXIT]" >&2; exit 2 ;;
esac
