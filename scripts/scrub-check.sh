#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Fails when the repository contains material that must not be published.
#
# Two sets of rules:
#   * generic rules, written below: secrets and keys, e-mail addresses, money amounts, ISO dates, internal id
#     formats, commercial vocabulary;
#   * private rules, kept outside the repository in the file named by SCRUB_PRIVATE_PATTERNS: one extended regex
#     per line, matched case-insensitively; empty lines and lines starting with '#' are ignored. Use them for
#     names that must not appear in the repository at all, not even inside this script.
#
# Without SCRUB_PRIVATE_PATTERNS only the generic rules run (a warning says so). With it set to a file that does
# not exist, the script stops with exit code 2.
#
# Scans every file git knows about (tracked, or untracked and not ignored). Works with the bash 3.2 that ships
# with macOS. Exit code: 0 clean, 1 findings, 2 configuration error.
set -uo pipefail

root="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
cd "$root" || exit 2

# One check per index: name, extended regex, extra grep flags, path-exclusion regex ("" = none), and whether the
# rule may also look at this script (the generic patterns are written here, so they would match themselves).
names=()
regexes=()
flags=()
excludes=()
self=()
add() { names+=("$1"); regexes+=("$2"); flags+=("$3"); excludes+=("$4"); self+=("$5"); }

add issue-id       '(^|[^A-Za-z0-9_/.$-])[BEMDS][0-9]{1,4}([^A-Za-z0-9_]|$)' ""   ""  no
add audit-cycle-id '(^|[^A-Za-z0-9])(it|IT)-[0-9]{2,4}([^0-9]|$)'           ""   ""  no
add task-id        '(^|[^A-Za-z0-9])T-[0-9]{3}([^0-9]|$)'                    ""   ""  no
add iso-date       '(^|[^0-9])20[0-9]{2}-[01][0-9]-[0-3][0-9]([^0-9]|$)'     ""   '(^|/)src/test/|^CHANGELOG\.md$' no
add email          '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}'          ""   ""  no
add money          '(€|EUR ?[0-9]|[0-9] ?EUR([^A-Za-z]|$))'                  ""   ""  no
add commercial     '(stripe|listino|crediti|(^|[^a-z])credits?([^a-z]|$)|pricing)' "-i" "" no
add private-key    '-----BEGIN [A-Z ]*PRIVATE KEY-----'                      ""   ""  no
add aws-key        'AKIA[0-9A-Z]{16}'                                        ""   ""  no
add live-key       '(sk_live_|rk_live_|xox[bp]-|ghp_[A-Za-z0-9]{20,})'       ""   ""  no

if [ -z "${SCRUB_PRIVATE_PATTERNS+set}" ]; then
  echo "scrub-check: warning: SCRUB_PRIVATE_PATTERNS is not set, only the generic rules run" >&2
else
  private_file="$SCRUB_PRIVATE_PATTERNS"
  if [ ! -f "$private_file" ] || [ ! -r "$private_file" ]; then
    echo "scrub-check: SCRUB_PRIVATE_PATTERNS names a file that does not exist or cannot be read" >&2
    exit 2
  fi
  n=0
  loaded=0
  while IFS= read -r line || [ -n "$line" ]; do
    n=$((n + 1))
    line="${line%$'\r'}"
    trimmed="$(printf '%s' "$line" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
    case "$trimmed" in
      ''|'#'*) continue ;;
    esac
    printf '' | grep -E -i -e "$trimmed" >/dev/null 2>&1
    if [ $? -eq 2 ]; then
      echo "scrub-check: invalid regular expression on line $n of the private patterns file" >&2
      exit 2
    fi
    add "private-pattern" "$trimmed" "-i" "" yes
    loaded=$((loaded + 1))
  done < "$private_file"
  if [ $loaded -eq 0 ]; then
    echo "scrub-check: the private patterns file contains no pattern" >&2
    exit 2
  fi
fi

files=()
while IFS= read -r f; do
  case "$f" in
    LICENSE|mvnw|mvnw.cmd) ;;
    *) [ -f "$f" ] && files+=("$f") ;;
  esac
done < <(git ls-files -co --exclude-standard)

status=0
for f in "${files[@]}"; do
  base="$(basename "$f")"
  case "$base" in
    .env.example) ;;
    .env|.env.*) echo "env-file: $f (real .env files never belong in the repository)"; status=1 ;;
  esac
done

i=0
while [ $i -lt ${#names[@]} ]; do
  name="${names[$i]}"; regex="${regexes[$i]}"; flag="${flags[$i]}"; exclude="${excludes[$i]}"
  for f in "${files[@]}"; do
    if [ "$f" = "scripts/scrub-check.sh" ] && [ "${self[$i]}" = "no" ]; then
      continue
    fi
    if [ -n "$exclude" ] && [[ "$f" =~ $exclude ]]; then
      continue
    fi
    if [ -n "$flag" ]; then
      hits="$(grep -n -E "$flag" -e "$regex" -- "$f" 2>/dev/null)"
    else
      hits="$(grep -n -E -e "$regex" -- "$f" 2>/dev/null)"
    fi
    if [ -n "$hits" ]; then
      while IFS= read -r hit; do
        echo "$name: $f:$hit"
      done <<<"$hits"
      status=1
    fi
  done
  i=$((i + 1))
done

if [ $status -ne 0 ]; then
  echo "scrub-check: FAILED - remove the material above before committing." >&2
else
  echo "scrub-check: clean (${#files[@]} files)"
fi
exit $status
