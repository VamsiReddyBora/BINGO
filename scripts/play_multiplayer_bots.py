#!/usr/bin/env python3
"""
Multiplayer Bingo Bot Companion - 5-Player Real-World Simulation
Simulates 4 autonomous bot companions (Kevin, Stuart, Otto, Phil) playing
alongside the real user (Bob) with dynamic, realistic behaviors:
1. Random Minimizing (AWAY - Yellow ring 🟡) with brief absence, then returning online.
2. Random Network Glitch / Disconnecting (OFFLINE - Red ring 🔴) with brief absence, then reconnecting.
3. Mid-game Surrender: Around Turn 12-14, Phil surrenders due to "low battery", leaving 4 players to finish.
4. Human-like pick delays (1.5 - 2.2s), in-game chat banter, and BINGO detection.
"""

import sys
import os
import time
import json
import random
import threading
import ssl
import urllib.request
import urllib.parse
from typing import Dict, List, Optional, Set
import paho.mqtt.client as mqtt

# --- Configuration ---
BROKER_HOST = "p0812f88.ala.asia-southeast1.emqxsl.com"
BROKER_PORT = 8883
BROKER_USER = "Bora"
BROKER_PASS = "bora7989"

KEYVALUE_API_URL = "https://keyvalue.immanuel.co/api/KeyVal"
KEYVALUE_APP_KEY = "01c1ad298a0c4f8287754f910ea0aa4b"

# 4 Bot Companions
BOT_KEVIN = {"id": "player_kevin", "username": "kevin", "displayName": "Kevin", "isHost": True}
BOT_STUART = {"id": "player_stuart", "username": "stuart", "displayName": "Stuart", "isHost": False}
BOT_OTTO = {"id": "player_otto", "username": "otto", "displayName": "Otto", "isHost": False}
BOT_PHIL = {"id": "player_phil", "username": "phil", "displayName": "Phil", "isHost": False}

BOTS = [BOT_KEVIN, BOT_STUART, BOT_OTTO, BOT_PHIL]

# --- Deterministic Java Random & Shuffler ---
class JavaRandom:
    def __init__(self, seed: int):
        self.seed = (seed ^ 0x5DEECE66D) & ((1 << 48) - 1)

    def next(self, bits: int) -> int:
        self.seed = (self.seed * 0x5DEECE66D + 0xB) & ((1 << 48) - 1)
        return self.seed >> (48 - bits)

    def next_int(self, n: int) -> int:
        if (n & -n) == n:
            return (n * self.next(31)) >> 31
        bits = self.next(31)
        val = bits % n
        while (bits - val + (n - 1)) < 0:
            bits = self.next(31)
            val = bits % n
        return val

def java_shuffle(lst: list, seed: int) -> list:
    rng = JavaRandom(seed)
    shuffled = list(lst)
    for i in range(len(shuffled), 1, -1):
        j = rng.next_int(i)
        shuffled[i - 1], shuffled[j] = shuffled[j], shuffled[i - 1]
    return shuffled

def count_completed_lines(board: List[int], marked: Set[int]) -> int:
    lines = 0
    # Rows
    for r in range(5):
        if all(board[r * 5 + c] in marked for c in range(5)):
            lines += 1
    # Cols
    for c in range(5):
        if all(board[r * 5 + c] in marked for r in range(5)):
            lines += 1
    # Diagonals
    if all(board[i * 5 + i] in marked for i in range(5)):
        lines += 1
    if all(board[i * 5 + (4 - i)] in marked for i in range(5)):
        lines += 1
    return lines

class MultiplayerBotCompanion:
    def __init__(self, room_code: Optional[str] = None):
        self.room_code = (room_code or "KV5000").upper()
        self.topic = f"bingo/v3/room/{self.room_code}"
        self.running = True
        self.lock = threading.Lock()

        # Network
        self.client_id = f"sim_multi_{random.randint(1000, 9999)}"
        self.client = mqtt.Client(client_id=self.client_id, protocol=mqtt.MQTTv311)
        self.client.username_pw_set(BROKER_USER, BROKER_PASS)
        self.client.tls_set(cert_reqs=ssl.CERT_NONE)
        self.client.tls_insecure_set(True)

        self.client.on_connect = self._on_connect
        self.client.on_message = self._on_message
        self.client.on_disconnect = self._on_disconnect

        # Match State
        self.in_game = False
        self.match_started = False
        self.match_seed = 0
        self.board_size = 5
        self.turn_number = 1
        self.current_turn_player_id = ""
        self.turn_order: List[str] = []
        self.picked_numbers: List[int] = []
        self.picked_by: List[str] = []
        self.marked: Set[int] = set()

        # Opponent (Bob / User)
        self.opponent_id = ""
        self.opponent_name = "Opponent"
        self.opponent_ready = False

        # Bots Boards & States
        self.bot_boards: Dict[str, List[int]] = {}
        # Bot status: "ONLINE", "AWAY", "OFFLINE", "SURRENDERED"
        self.bot_status: Dict[str, str] = {b["id"]: "ONLINE" for b in BOTS}
        self.surrendered_player_ids: Set[str] = set()

        # Real-World Simulation Flags
        self.phil_has_surrendered = False
        self.active_simulation_bot: Optional[str] = None # At most 1 bot in transient state at a time

    def start(self):
        print("\n" + "="*65)
        print("  🤖 MULTIPLAYER BINGO COMPANION - 5 PLAYERS")
        print("="*65)
        print(f"Room Code:   {self.room_code}")
        print(f"Topic:       {self.topic}")
        print(f"Host:        Kevin 👑")
        print(f"Companions:  Stuart, Otto, Phil")
        print("="*65 + "\n")

        print("[Network] Connecting to MQTT broker...")
        self.client.connect(BROKER_HOST, BROKER_PORT, 60)
        self.client.loop_start()

        # Register room metadata for cloud discovery
        self._register_room_metadata()

        # Start background heartbeat loop
        threading.Thread(target=self._heartbeat_loop, daemon=True).start()

    def _register_room_metadata(self):
        """Registers room session with all 4 bots so app sees them in lobby."""
        players_meta = [
            {"id": b["id"], "displayName": b["displayName"], "username": b["username"], "isHost": b["isHost"], "lobbyReadyStatus": "READY"}
            for b in BOTS
        ]
        session = {
            "roomCode": self.room_code,
            "hostId": BOT_KEVIN["id"],
            "hostUsername": BOT_KEVIN["username"],
            "hostDisplayName": BOT_KEVIN["displayName"],
            "status": "WAITING",
            "boardSize": 5,
            "createdAt": int(time.time() * 1000),
            "lastHeartbeat": int(time.time() * 1000),
            "players": players_meta,
            "currentSeed": 0,
            "isManualBoard": False,
            "isDynamicBoard": False
        }
        json_str = json.dumps(session)
        meta_topic = f"bingo/v3/room_meta/{self.room_code}"
        self.client.publish(meta_topic, json_str, qos=1, retain=True)
        print(f"[Registry] Published 4-bot metadata to retained topic '{meta_topic}'")

        try:
            url = f"{KEYVALUE_API_URL}/UpdateValue/{KEYVALUE_APP_KEY}/room_{self.room_code}?value={urllib.parse.quote(json_str)}"
            req = urllib.request.Request(url, data=b"", headers={"Content-Length": "0"}, method="POST")
            with urllib.request.urlopen(req, timeout=3.0) as resp:
                print(f"[Registry] Registered room {self.room_code} on KeyValue cloud registry (status={resp.status})")
        except Exception as e:
            print(f"[Registry] KeyValue registration note: {e}")

    def _on_connect(self, client, userdata, flags, rc, properties=None):
        if rc == 0:
            print("[Network] Connected to broker successfully!")
            client.subscribe(self.topic, qos=1)
            print(f"[Network] Subscribed to room topic: {self.topic}")
            time.sleep(0.3)
            self._send_lobby_presences()
        else:
            print(f"[Network] Connection failed with rc={rc}")

    def _on_disconnect(self, client, userdata, *args, **kwargs):
        if self.running:
            print("[Network] Disconnected from broker. Auto-reconnecting...")

    def publish(self, payload: str):
        if self.client and self.running:
            self.client.publish(self.topic, payload, qos=1)

    def send_chat(self, text: str, sender: dict = BOT_KEVIN):
        pkt = {
            "type": "CHAT_MESSAGE",
            "playerId": sender["id"],
            "displayName": text,
            "username": sender["displayName"],
            "seed": self.match_seed,
            "timestamp": int(time.time() * 1000)
        }
        self.publish(json.dumps(pkt))
        print(f"💬 [{sender['displayName']}] \"{text}\"")

    def _send_lobby_presences(self):
        now_ms = int(time.time() * 1000)
        for i, b in enumerate(BOTS):
            is_host_flag = 1 if b["isHost"] else 0
            hb = f"H|{b['id']}|{b['displayName']}|{is_host_flag}|{now_ms}|{b['username']}||READY|{i+1}|5"
            self.publish(hb)
            ready_micro = f"R|{b['id']}|READY|{b['username']}|{b['displayName']}|{is_host_flag}|sim_bot|1|{i+1}|5"
            self.publish(ready_micro)

    def _heartbeat_loop(self):
        while self.running:
            try:
                time.sleep(2.5)
                if not self.in_game:
                    self._send_lobby_presences()
                else:
                    for b in BOTS:
                        status = self.bot_status.get(b["id"], "ONLINE")
                        if status == "ONLINE":
                            now_ms = int(time.time() * 1000)
                            mhb = f"MHB|1|{self.match_seed}|ACTIVE|{b['id']}|{self.opponent_id}|sim_bot|{self.turn_number}|none|{now_ms}"
                            self.publish(mhb)
            except Exception:
                pass

    def _on_message(self, client, userdata, msg):
        try:
            payload = msg.payload.decode("utf-8").strip()
            self._handle_room_message(payload)
        except Exception as e:
            print(f"[Error] on_message: {e}")

    def _handle_room_message(self, payload: str):
        # 1. Heartbeat / Ready from Bob (Micro format)
        if payload.startswith("H|") or payload.startswith("R|"):
            parts = payload.split("|")
            pid = parts[1] if len(parts) > 1 else ""
            if payload.startswith("H|"):
                name = parts[2] if len(parts) > 2 else "Bob"
                is_ready = any(p == "READY" for p in parts[3:])
            else: # R|
                name = parts[4] if len(parts) > 4 and parts[4] else "Bob"
                is_ready = any(p == "READY" for p in parts[1:])

            # If not a bot, it's the real user!
            if pid and not pid.startswith("player_"):
                if self.opponent_id != pid:
                    self.opponent_id = pid
                    self.opponent_name = name or "Bob"
                    print(f"👋 [LOBBY] Real player '{self.opponent_name}' ({pid}) joined!")
                    self.send_chat(f"Welcome {self.opponent_name}! We're all ready to play! 👑", BOT_KEVIN)
                    self.send_chat(f"Hey {self.opponent_name}! Let's do this! 🍌", BOT_STUART)

                if is_ready and not self.opponent_ready:
                    self.opponent_ready = True
                    print(f"✅ [LOBBY] {self.opponent_name} tapped READY!")
                    if not self.match_started:
                        threading.Thread(target=self._start_countdown, daemon=True).start()

        # 2. JSON Packets
        elif payload.startswith("{") and payload.endswith("}"):
            try:
                data = json.loads(payload)
                pkt_type = data.get("type", "")
                pid = data.get("playerId", "")

                # Check for lobby join / ready in JSON
                if pid and not pid.startswith("player_") and not self.in_game:
                    name = data.get("displayName") or data.get("username") or "Bob"
                    if self.opponent_id != pid:
                        self.opponent_id = pid
                        self.opponent_name = name
                        print(f"👋 [LOBBY] Real player '{self.opponent_name}' ({pid}) joined!")
                        self.send_chat(f"Welcome {self.opponent_name}! We're all ready to play! 👑", BOT_KEVIN)
                        self.send_chat(f"Hey {self.opponent_name}! Let's do this! 🍌", BOT_STUART)

                    if (data.get("readyStatus") == "READY" or pkt_type in ("READY", "PLAYER_READY")) and not self.opponent_ready:
                        self.opponent_ready = True
                        print(f"✅ [LOBBY] {self.opponent_name} tapped READY!")
                        if not self.match_started:
                            threading.Thread(target=self._start_countdown, daemon=True).start()

                if pkt_type == "PICK_NUMBER":
                    num = data.get("number", 0)
                    picker = data.get("playerId", "")
                    turn_num = data.get("turnNumber", self.turn_number)
                    next_turn = data.get("currentTurnPlayerId", "")
                    self._on_pick_received(num, picker, turn_num, next_turn)
                elif pkt_type == "CHAT_MESSAGE":
                    sender = data.get("username", "Opponent")
                    text = data.get("displayName", "")
                    if not pid.startswith("player_") and text:
                        print(f"💬 [{sender}] \"{text}\"")
            except Exception:
                pass

        # 3. Micro Pick
        elif payload.startswith("P|"):
            parts = payload.split("|")
            num = int(parts[1]) if len(parts) > 1 and (parts[1].isdigit() or parts[1] == "-1") else 0
            picker = parts[2] if len(parts) > 2 else ""
            turn_num = int(parts[3]) if len(parts) > 3 and parts[3].isdigit() else self.turn_number
            next_turn = parts[4] if len(parts) > 4 else ""
            self._on_pick_received(num, picker, turn_num, next_turn)

    def _start_countdown(self):
        self.match_started = True
        seed = int(time.time() * 1000) % 100000000
        all_players = [
            {"id": BOT_KEVIN["id"], "displayName": BOT_KEVIN["displayName"], "username": BOT_KEVIN["username"], "isHost": True},
            {"id": BOT_STUART["id"], "displayName": BOT_STUART["displayName"], "username": BOT_STUART["username"], "isHost": False},
            {"id": BOT_OTTO["id"], "displayName": BOT_OTTO["displayName"], "username": BOT_OTTO["username"], "isHost": False},
            {"id": BOT_PHIL["id"], "displayName": BOT_PHIL["displayName"], "username": BOT_PHIL["username"], "isHost": False},
            {"id": self.opponent_id, "displayName": self.opponent_name, "username": self.opponent_name.lower(), "isHost": False}
        ]

        # Deterministic turn order
        pids = [p["id"] for p in all_players]
        shuffled_pids = java_shuffle(sorted(pids), seed)
        first_turn = shuffled_pids[0]

        print(f"\n🚀 [HOST] All 5 players READY! Starting 3-second synchronized countdown...")
        self.send_chat("All ready! Game begins in 3... 2... 1... 🚀", BOT_KEVIN)

        start_pkt = {
            "type": "START_GAME",
            "boardSize": 5,
            "seed": seed,
            "matchId": str(seed),
            "protocolVersion": 0, # Multi-player protocol
            "playerId": BOT_KEVIN["id"],
            "currentTurnPlayerId": first_turn,
            "isManualBoard": False,
            "isDynamicBoard": False,
            "players": all_players
        }
        json_start = json.dumps(start_pkt)
        self.publish(json_start)
        time.sleep(0.15)
        self.publish(json_start)

        for c in [3, 2, 1]:
            print(f"  ⏱️  Countdown: {c}...")
            time.sleep(1.0)
        print("  🚀 5-PLAYER GAME BEGINS NOW!")

        # Initial chat message announcing host
        time.sleep(0.5)
        self.send_chat("👑 Kevin is the host", BOT_KEVIN)

        self._start_game(seed, all_players, shuffled_pids, first_turn)

    def _start_game(self, seed: int, all_players: list, turn_order: list, first_turn: str):
        if self.in_game:
            return
        self.in_game = True
        self.match_seed = seed
        self.turn_number = 1
        self.turn_order = turn_order
        self.current_turn_player_id = first_turn
        self.picked_numbers.clear()
        self.picked_by.clear()
        self.marked.clear()

        # Generate distinct boards for each bot
        base = list(range(1, 26))
        for i, b in enumerate(BOTS):
            self.bot_boards[b["id"]] = java_shuffle(base, seed + (i + 1) * 31337)

        print("\n" + "="*65)
        print("🎮 🎮 🎮  5-PLAYER MATCH STARTED!  🎮 🎮 🎮")
        print(f"Room Code:       {self.room_code}")
        print(f"Match Seed:      {self.match_seed}")
        print(f"Turn Order:      {self.turn_order}")
        first_name = self._get_player_name(first_turn)
        print(f"First Turn:      {first_name}")
        print("="*65 + "\n")

        # Start dynamic simulation manager (handles random minimize, disconnect, surrender)
        threading.Thread(target=self._dynamic_simulation_loop, daemon=True).start()

        self._check_and_trigger_turn()

    def _get_player_name(self, pid: str) -> str:
        for b in BOTS:
            if b["id"] == pid:
                return b["displayName"]
        if pid == self.opponent_id:
            return self.opponent_name
        return pid

    def _get_next_player(self, current_pid: str) -> str:
        active = [p for p in self.turn_order if p not in self.surrendered_player_ids]
        if not active:
            return ""
        if current_pid in active:
            idx = active.index(current_pid)
            return active[(idx + 1) % len(active)]
        return active[0]

    def _on_pick_received(self, num: int, picker: str, turn_num: int, next_turn: str):
        picker_name = self._get_player_name(picker)

        if num > 0 and num not in self.picked_numbers:
            self.picked_numbers.append(num)
            self.picked_by.append(picker)
            self.marked.add(num)
            print(f"🎲 [Turn {turn_num:02d}] {picker_name} picked #{num:02d}")
        elif num <= 0:
            print(f"⏱️ [Turn {turn_num:02d}] Turn skipped/passed by {picker_name}")

        self.turn_number = max(self.turn_number, turn_num + 1)

        # Calculate next turn
        calculated_next = next_turn or self._get_next_player(picker)
        self.current_turn_player_id = calculated_next
        next_name = self._get_player_name(self.current_turn_player_id)
        print(f"   ➔ Next Turn: {next_name} (Turn {self.turn_number})")

        # Check line completions for bots
        for b in BOTS:
            if b["id"] not in self.surrendered_player_ids:
                lines = count_completed_lines(self.bot_boards[b["id"]], self.marked)
                if lines >= 5:
                    print(f"\n🎉 🎉 🎉 BINGO! {b['displayName'].upper()} HAS 5 LINES! 🎉 🎉 🎉")
                    self.send_chat(f"BINGO!! What an awesome match! GG! 👑🎉", b)
                    self.publish(f"BINGO|{b['id']}|{lines}|{num}|{self.match_seed}")
                    return

        self._check_and_trigger_turn()

    def _check_and_trigger_turn(self):
        curr_id = self.current_turn_player_id
        if not self.in_game or not curr_id:
            return

        # If it's a bot's turn:
        if curr_id.startswith("player_"):
            # Check if bot is currently away or offline
            status = self.bot_status.get(curr_id, "ONLINE")
            if status != "ONLINE":
                print(f"   ⏳ [{self._get_player_name(curr_id)}] is currently {status}. Will not pick; app timer will count down and skip!")
                return

            threading.Thread(target=self._execute_bot_pick, args=(curr_id,), daemon=True).start()

    def _execute_bot_pick(self, bot_id: str):
        bot = next((b for b in BOTS if b["id"] == bot_id), None)
        if not bot:
            return

        # Human-like think delay (1.5 - 2.2 seconds)
        think_delay = random.uniform(1.5, 2.2)
        time.sleep(think_delay)

        with self.lock:
            if not self.in_game or self.current_turn_player_id != bot_id:
                return
            if self.bot_status.get(bot_id) != "ONLINE":
                return

            board = self.bot_boards[bot_id]
            unmarked = [n for n in board if n not in self.picked_numbers]
            if not unmarked:
                return

            chosen_num = unmarked[0]
            self.picked_numbers.append(chosen_num)
            self.picked_by.append(bot_id)
            self.marked.add(chosen_num)

            curr_turn = self.turn_number
            next_turn_num = curr_turn + 1
            next_player = self._get_next_player(bot_id)

            self.turn_number = next_turn_num
            self.current_turn_player_id = next_player

            history_str = ",".join(str(x) for x in self.picked_numbers)
            picked_by_str = ",".join(self.picked_by)

            # Broadcast micro-packet
            micro = f"P|{chosen_num}|{bot_id}|{curr_turn}|{next_player}|{history_str}|{self.match_seed}|sim_bot|{picked_by_str}"
            self.publish(micro)

            # Broadcast JSON packet
            json_pick = {
                "type": "PICK_NUMBER",
                "number": chosen_num,
                "playerId": bot_id,
                "turnNumber": curr_turn,
                "pickedHistory": self.picked_numbers,
                "pickedByHistory": self.picked_by,
                "currentTurnPlayerId": next_player,
                "seed": self.match_seed,
                "matchId": str(self.match_seed),
                "timestamp": int(time.time() * 1000)
            }
            self.publish(json.dumps(json_pick))

            lines = count_completed_lines(board, self.marked)
            next_name = self._get_player_name(next_player)
            print(f"👉 [Turn {curr_turn:02d}] {bot['displayName']} picked #{chosen_num:02d} | Lines: {lines} | Next: {next_name}")

            # Friendly banter
            if lines == 3:
                self.send_chat("3 lines! Making progress! 🎯", bot)
            elif lines == 4:
                self.send_chat("4 lines! Just one more for BINGO! ⚡", bot)
            elif lines >= 5:
                print(f"\n🎉 🎉 🎉 BINGO! {bot['displayName'].upper()} REACHED 5 LINES! 🎉 🎉 🎉")
                self.send_chat("BINGO!! What a game! GG everyone! 👑🎉", bot)
                self.publish(f"BINGO|{bot_id}|{lines}|{chosen_num}|{self.match_seed}")

    # =========================================================================
    # DYNAMIC REAL-WORLD SIMULATION (Random Minimize, Disconnect, Surrender)
    # =========================================================================
    def _dynamic_simulation_loop(self):
        """Orchestrates random real-world events across the 4 bots."""
        time.sleep(10.0) # Let the match get underway first (Turns 1-4)

        while self.running and self.in_game:
            # Random interval between events: 12 to 20 seconds
            wait_time = random.uniform(12.0, 20.0)
            time.sleep(wait_time)

            if not self.in_game or not self.running:
                break

            # Check if it's time for Phil to surrender (Turn 12-16)
            if not self.phil_has_surrendered and self.turn_number >= 12:
                self._simulate_phil_surrender()
                continue

            # Pick a bot eligible for a transient event (must be ONLINE and not surrendered)
            eligible_bots = [
                b for b in BOTS 
                if self.bot_status[b["id"]] == "ONLINE" and b["id"] not in self.surrendered_player_ids
            ]
            if not eligible_bots or self.active_simulation_bot is not None:
                continue

            chosen_bot = random.choice(eligible_bots)
            event_type = random.choice(["AWAY", "OFFLINE"])

            if event_type == "AWAY":
                threading.Thread(target=self._simulate_transient_away, args=(chosen_bot,), daemon=True).start()
            else:
                threading.Thread(target=self._simulate_transient_offline, args=(chosen_bot,), daemon=True).start()

    def _simulate_transient_away(self, bot: dict):
        bot_id = bot["id"]
        bot_name = bot["displayName"]
        self.active_simulation_bot = bot_id
        self.bot_status[bot_id] = "AWAY"

        print(f"\n🟡 [SIMULATION] {bot_name} MINIMIZED the game! (Sending AWAY presence)")
        away_pkt = {
            "type": "PLAYER_PRESENCE",
            "playerId": bot_id,
            "displayName": bot_name,
            "username": bot["username"],
            "readyStatus": "AWAY",
            "timestamp": int(time.time() * 1000)
        }
        self.publish(json.dumps(away_pkt))

        # Stay away for 8 to 11 seconds
        away_duration = random.uniform(8.0, 11.0)
        time.sleep(away_duration)

        if not self.running or not self.in_game:
            return

        # Reconnect / Return online
        print(f"🟢 [SIMULATION] {bot_name} RETURNED! (Sending ONLINE presence)")
        online_pkt = {
            "type": "PLAYER_PRESENCE",
            "playerId": bot_id,
            "displayName": bot_name,
            "username": bot["username"],
            "readyStatus": "ONLINE",
            "timestamp": int(time.time() * 1000)
        }
        self.publish(json.dumps(online_pkt))
        self.bot_status[bot_id] = "ONLINE"
        self.active_simulation_bot = None

        return_chats = [
            f"Back! Phone call ended 📱🟢",
            f"I'm back! Let's continue 😄",
            f"Sorry, had to reply to a message! 🟢"
        ]
        self.send_chat(random.choice(return_chats), bot)
        self._check_and_trigger_turn()

    def _simulate_transient_offline(self, bot: dict):
        bot_id = bot["id"]
        bot_name = bot["displayName"]
        self.active_simulation_bot = bot_id
        self.bot_status[bot_id] = "OFFLINE"

        print(f"\n🔴 [SIMULATION] {bot_name} DISCONNECTED! (Sending OFFLINE presence)")
        offline_pkt = {
            "type": "PLAYER_PRESENCE",
            "playerId": bot_id,
            "displayName": bot_name,
            "username": bot["username"],
            "readyStatus": "OFFLINE",
            "timestamp": int(time.time() * 1000)
        }
        self.publish(json.dumps(offline_pkt))

        # Stay offline for 6 to 9 seconds
        disconnect_duration = random.uniform(6.0, 9.0)
        time.sleep(disconnect_duration)

        if not self.running or not self.in_game:
            return

        # Reconnect online
        print(f"🟢 [SIMULATION] {bot_name} RECONNECTED! (Sending ONLINE presence)")
        online_pkt = {
            "type": "PLAYER_PRESENCE",
            "playerId": bot_id,
            "displayName": bot_name,
            "username": bot["username"],
            "readyStatus": "ONLINE",
            "timestamp": int(time.time() * 1000)
        }
        self.publish(json.dumps(online_pkt))
        self.bot_status[bot_id] = "ONLINE"
        self.active_simulation_bot = None

        reconnect_chats = [
            f"Wi-Fi hiccup! Reconnected 📶🟢",
            f"Lost network for a second, I'm back! 🟢",
            f"Signal restored! 🚀"
        ]
        self.send_chat(random.choice(reconnect_chats), bot)
        self._check_and_trigger_turn()

    def _simulate_phil_surrender(self):
        """Simulates Phil surrendering around Turn 12-14 due to low battery."""
        self.phil_has_surrendered = True
        phil_id = BOT_PHIL["id"]
        self.surrendered_player_ids.add(phil_id)
        self.bot_status[phil_id] = "SURRENDERED"

        print(f"\n🚪 [SIMULATION] PHIL IS SURRENDERING THE MATCH! (Turn {self.turn_number})")
        self.send_chat("Battery is at 1%! Have to surrender, good luck everyone! 👋🔋", BOT_PHIL)
        time.sleep(0.5)

        surrender_pkt = {
            "type": "SURRENDER",
            "seed": self.match_seed,
            "matchId": str(self.match_seed),
            "playerId": phil_id,
            "displayName": BOT_PHIL["displayName"],
            "username": BOT_PHIL["username"],
            "isHost": False
        }
        self.publish(json.dumps(surrender_pkt))
        print("  ➜ Sent SURRENDER packet for Phil. Phil is now eliminated!")
        print("  ➜ Match continues seamlessly for Bob, Kevin, Stuart, and Otto!")

        # If it was Phil's turn, advance turn immediately
        if self.current_turn_player_id == phil_id:
            next_player = self._get_next_player(phil_id)
            self.current_turn_player_id = next_player
            self.turn_number += 1
            print(f"  ➜ Advanced turn from surrendered Phil to {self._get_player_name(next_player)}!")
            self._check_and_trigger_turn()

    def stop(self):
        self.running = False
        if self.client:
            self.client.loop_stop()
            self.client.disconnect()

def main():
    import argparse
    parser = argparse.ArgumentParser(description="Multiplayer 5-Player Bingo Companion with Real-World Network Simulation")
    parser.add_argument("--room", type=str, default="KV5000", help="Room code for Kevin to host (default: KV5000)")
    args = parser.parse_args()

    companion = MultiplayerBotCompanion(room_code=args.room)
    companion.start()

    try:
        while companion.running:
            time.sleep(1.0)
    except KeyboardInterrupt:
        print("\nStopping multiplayer bots...")
        companion.stop()

if __name__ == "__main__":
    main()
