"""Drive real Android process exits against the isolated recovery backend.

Uses the installed production Activity and UI touches. Fixture instrumentation only
seeds/inspects synthetic storage; it never sends or recovers the assistant request.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.onmyway.app"


class Evaluation:
    def __init__(self, serial: str, output: Path):
        self.serial = serial
        self.output = output.resolve()
        self.output.relative_to(ROOT / "app" / "build" / "reports")
        self.output.mkdir(parents=True, exist_ok=True)
        driver = Path(__file__).read_bytes()
        (self.output / "driver.py").write_bytes(driver)
        self.save("manifest.json", {
            "driver_sha256": hashlib.sha256(driver).hexdigest(),
            "apk_sha256": hashlib.sha256((ROOT / "app/build/outputs/apk/debug/app-debug.apk").read_bytes()).hexdigest(),
        })
        self.adb = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe"
        self.events: list[dict] = []
        self.counter = 0

    def command(self, args: list[str], timeout: int = 90) -> str:
        result = subprocess.run(args, cwd=ROOT, capture_output=True, timeout=timeout)
        text = result.stdout.decode("utf-8", "replace")
        if result.returncode:
            raise RuntimeError(f"Command failed ({result.returncode}): {args}\n{text}\n"
                               + result.stderr.decode("utf-8", "replace"))
        return text

    def device(self, *args: str, timeout: int = 90) -> str:
        return self.command([str(self.adb), "-s", self.serial, *args], timeout)

    def api(self, path: str, body: dict | None = None) -> dict:
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request("http://127.0.0.1:8000" + path, data=data,
                                         headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=10) as response:
            return json.load(response)

    def save(self, name: str, value) -> None:
        (self.output / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    def wait(self, predicate, message: str, timeout: int = 30):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            value = predicate()
            if value:
                return value
            time.sleep(0.3)
        raise AssertionError(message)

    def health(self) -> None:
        assert "package:" in self.device("shell", "pm", "path", PACKAGE)
        assert self.api("/health")["status"] == "ok"
        self.device("reverse", "tcp:8000", "tcp:8000")
        response = self.device("shell", "(printf 'GET /health HTTP/1.0\\r\\nHost: localhost\\r\\n\\r\\n'; sleep 2) | toybox nc -w 5 127.0.0.1 8000")
        assert "200 OK" in response and re.search(r'"status"\s*:\s*"ok"', response), response
        self.events.append({"event": "post_install_device_health", "http": 200, "status": "ok"})

    def fixture(self, phase: str, **kwargs) -> dict:
        self.counter += 1
        args = ["shell", "am", "instrument", "-w", "-r", "-e", "class",
                "com.onmyway.app.ProcessRecoveryFixtureTest#runPhase",
                "-e", "isolatedProcessRecovery", "true", "-e", "phase", phase]
        for key, value in kwargs.items():
            args += ["-e", key, str(value)]
        args += [PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"]
        try:
            result = self.device(*args)
            (self.output / f"fixture-{self.counter}-{phase}.log").write_text(result, encoding="utf-8")
        finally:
            # Instrumentation can remove its target even on a tool timeout/error.
            env = dict(os.environ, ANDROID_SERIAL=self.serial)
            install = subprocess.run([str(ROOT / "gradlew.bat"), ":app:installDebug", "--console=plain"],
                                     cwd=ROOT, env=env, capture_output=True, timeout=180)
            (self.output / f"install-{self.counter}.log").write_bytes(install.stdout + install.stderr)
            assert install.returncode == 0, "Post-instrumentation install failed"
            self.health()
        assert "OK (1 test)" in result and "FAILURES!!!" not in result, result
        observation = json.loads(self.device("exec-out", "run-as", PACKAGE, "cat", "files/process-recovery-observation.json"))
        self.save(f"observation-{self.counter}-{phase}.json", observation)
        return observation

    def hierarchy(self) -> ET.Element:
        self.device("shell", "uiautomator", "dump", "/sdcard/process-recovery.xml")
        xml = self.device("exec-out", "cat", "/sdcard/process-recovery.xml")
        return ET.fromstring(xml[xml.index("<?xml"):])

    def capture(self, name: str) -> None:
        tree = self.hierarchy()
        ET.ElementTree(tree).write(self.output / f"{name}.xml", encoding="utf-8", xml_declaration=True)
        self.device("shell", "screencap", "-p", f"/sdcard/{name}.png")
        self.device("pull", f"/sdcard/{name}.png", str(self.output / f"{name}.png"))

    @staticmethod
    def bounds(node):
        numbers = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
        return numbers if len(numbers) == 4 else [0, 0, 0, 0]

    def find(self, label: str):
        for node in self.hierarchy().iter("node"):
            if label in (node.get("text"), node.get("content-desc")):
                left, top, right, bottom = self.bounds(node)
                if right > left and bottom > top and node.get("enabled") == "true":
                    return node
        return None

    def click(self, label: str, scroll: bool = False) -> None:
        for attempt in range(8):
            node = self.find(label)
            if node is not None:
                left, top, right, bottom = self.bounds(node)
                self.device("shell", "input", "tap", str((left + right)//2), str((top + bottom)//2))
                return
            if scroll:
                # Scroll the message column while leaving the composer untouched.
                self.device("shell", "input", "swipe", "550", "700" if attempt % 2 == 0 else "1650",
                            "550", "1650" if attempt % 2 == 0 else "700", "350")
            time.sleep(0.4)
        raise AssertionError(f"Visible enabled UI control not found: {label}")

    def cancellation_label(self):
        # The production adapter upgrades legacy create_task responses carrying
        # planned_date into the multi-draft card, including a one-item proposal.
        for label in ("取消剩余草稿", "取消"):
            if self.find(label) is not None:
                return label
        return None

    def launch(self) -> str:
        self.device("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
        pid = self.device("shell", "pidof", PACKAGE).strip()
        assert pid and " " not in pid, pid
        self.wait(lambda: any(self.find(label) is not None for label in
                              ("消息输入框", "继续获取结果", "继续核对路线", "行程更多操作")),
                  "MainActivity did not become ready", 45)
        return pid

    def kill(self, expected_pid: str) -> None:
        assert self.device("shell", "pidof", PACKAGE).strip() == expected_pid
        self.device("shell", "input", "keyevent", "KEYCODE_HOME")
        # SIGKILL under the debuggable app UID, without clearing storage or the task.
        self.device("shell", "run-as", PACKAGE, "kill", "-9", expected_pid)
        def absent():
            output = subprocess.run([str(self.adb), "-s", self.serial, "shell", "pidof", PACKAGE], capture_output=True)
            return expected_pid not in output.stdout.decode().split()
        self.wait(absent, "Old process survived SIGKILL")
        self.events.append({"event": "process_killed", "pid": expected_pid, "mechanism": "background + run-as SIGKILL"})

    def send(self) -> None:
        self.click("消息输入框")
        self.device("shell", "input", "text", "print%spaper")
        self.click("发送")
        self.wait(lambda: self.api("/__test/state").get("gate_entered"), "Production request did not enter the model gate", 25)

    def run(self, case: str) -> None:
        if case == "planning":
            self.run_planning()
            return
        self.api("/__test/reset", {"scenario": "reply" if case == "abandoned" else "print"})
        self.fixture("seed", mode="print_draft" if case == "abandoned" else "none")
        # Instrumentation has its own process; finish it before the production launch.
        self.device("shell", "am", "force-stop", PACKAGE)
        original_pid = self.launch()
        self.send()
        before = self.api("/__test/state")
        assert before["submit_count"] == 1 and before["model_calls"] == 1, before
        request_id = next(c["request_id"] for c in before["calls"] if c["path"] == "/assistant/chat")
        self.save(case + "-before-kill.json", before)
        self.kill(original_pid)
        if case == "completed":
            self.api("/__test/release", {})
            self.wait(lambda: self.api("/__test/state")["active_calls"] == 0, "Original request did not finish")
        new_pid = self.launch()
        assert new_pid != original_pid
        self.events.append({"event": "cold_recovery", "case": case, "old_pid": original_pid, "new_pid": new_pid, "request_id": request_id})
        self.wait(lambda: self.find("继续获取结果") is not None, "Original pending request not restored")
        assert self.api("/__test/state")["status_count"] == 0, "Startup queried without explicit action"
        self.capture(case + "-pending")
        if case == "abandoned":
            self.click("重新编辑", scroll=True)
            self.click(self.wait(self.cancellation_label, "Original print draft cancellation unavailable"), scroll=True)
            self.click("消息输入框")
            self.device("shell", "input", "keyevent", "KEYCODE_MOVE_END")
            self.device("shell", "input", "keyevent", *(["KEYCODE_DEL"] * len("print paper")))
            self.device("shell", "input", "keyevent", "KEYCODE_BACK")
            self.click("新对话")
            self.api("/__test/release", {})
            self.wait(lambda: self.api("/__test/state")["active_calls"] == 0, "Abandoned response did not finish")
            expected_status = 0
        else:
            self.click("继续获取结果", scroll=True)
            self.wait(lambda: self.api("/__test/state")["status_count"] == 1, "Recovery did not query original request")
            if case == "running":
                self.wait(lambda: self.find("继续获取结果") is not None, "Running request lost its recovery action")
                still_running = self.api("/__test/state")
                status_call = next(c for c in still_running["calls"] if c["path"] == "/assistant/chat/status")
                assert status_call["response"]["state"] == "running", status_call
                assert still_running["submit_count"] == 1
                self.api("/__test/release", {})
                self.wait(lambda: self.api("/__test/state")["active_calls"] == 0, "Gated request did not complete")
                self.click("继续获取结果", scroll=True)
                expected_status = 2
            else:
                expected_status = 1
            cancel_label = self.wait(self.cancellation_label, "Recovered print proposal was not displayed", 35)
            self.capture(case + "-recovered")
            self.click(cancel_label, scroll=True)
        self.wait(lambda: self.find("继续获取结果") is None, "Completed or abandoned pending request remained")
        self.kill(new_pid)
        final_pid = self.launch()
        assert final_pid not in (original_pid, new_pid)
        self.events.append({"event": "second_restart", "case": case, "old_pid": new_pid, "new_pid": final_pid})
        self.capture(case + "-after-cancel-restart")
        final = self.api("/__test/state")
        assert final["submit_count"] == 1 and final["model_calls"] == 1 and final["status_count"] == expected_status, final
        calls = [c for c in final["calls"] if c["path"].startswith("/assistant/chat")]
        assert len({c["request_id"] for c in calls}) == 1
        assert len({c["body_sha256"] for c in calls}) == 1
        self.save(case + "-backend-final.json", final)
        self.fixture("assert", expected="cancelled" if case != "abandoned" else "idle", requestId=request_id,
                     changedConversation=str(case == "abandoned").lower())
        self.events.append({"event": "case_passed", "case": case, "request_id": request_id, "submit": 1, "status": expected_status})
        self.save("events.json", self.events)

    def run_planning(self) -> None:
        self.api("/__test/reset", {"scenario": "planning"})
        self.fixture("seed", mode="planning")
        self.device("shell", "am", "force-stop", PACKAGE)
        old_pid = self.launch()
        self.click("顺路")
        self.click("调整")
        self.click("重新规划")
        self.wait(lambda: self.api("/__test/state")["gate_entered"], "Planning request did not reach response gate", 35)
        initial = self.api("/__test/state")
        assert initial["plan_submit_count"] == 1
        run_id = next(c["execution_id"] for c in initial["calls"] if c["path"] == "/plans")
        self.save("planning-before-kill.json", initial)
        self.kill(old_pid)
        self.api("/__test/release", {})
        self.wait(lambda: self.api("/__test/state")["active_calls"] == 0, "Original plan response did not finish")
        new_pid = self.launch()
        assert new_pid != old_pid
        self.events.append({"event": "cold_recovery", "case": "planning", "old_pid": old_pid, "new_pid": new_pid, "run_id": run_id})
        self.wait(lambda: self.find("继续核对路线") is not None, "Cold startup did not discover the original plan")
        assert self.api("/__test/state")["plan_status_count"] == 0
        self.capture("planning-pending")
        self.click("继续核对路线", scroll=True)
        self.wait(lambda: self.find("使用此方案") is not None, "Original route result was not recovered", 35)
        queried = self.api("/__test/state")
        assert queried["plan_submit_count"] == 1 and queried["plan_status_count"] == 1
        assert queried["map_calls"] == initial["map_calls"], "Recovery recalculated the route"
        self.capture("planning-preview")
        self.click("使用此方案")
        self.wait(lambda: self.find("使用此方案") is None, "Confirmed recovery still displayed its action")
        self.kill(new_pid)
        final_pid = self.launch()
        assert final_pid not in (old_pid, new_pid)
        self.events.append({"event": "second_restart", "case": "planning", "old_pid": new_pid, "new_pid": final_pid})
        self.capture("planning-confirmed-restart")
        final = self.api("/__test/state")
        assert final["plan_submit_count"] == 1 and final["plan_status_count"] == 1
        calls = [c for c in final["calls"] if c["path"] in ("/plans", "/plans/status")]
        assert len({c["execution_id"] for c in calls}) == 1
        assert len({c["body_sha256"] for c in calls}) == 1
        self.save("planning-backend-final.json", final)
        self.fixture("assert", expected="planning_recovered", runId=run_id)
        self.events.append({"event": "case_passed", "case": "planning", "run_id": run_id, "submit": 1, "status": 1})
        self.save("events.json", self.events)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--case", choices=["completed", "running", "abandoned", "planning", "all"], default="all")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("Only an explicitly selected isolated emulator is supported")
    evaluation = Evaluation(args.serial, args.output)
    assert "Pixel_7" in evaluation.device("emu", "avd", "name"), "Use the dedicated Pixel_7 data image"
    try:
        for case in (["completed", "running", "abandoned", "planning"] if args.case == "all" else [args.case]):
            print(f"Running {case}", flush=True)
            evaluation.run(case)
        evaluation.save("summary.json", {"status": "passed", "events": evaluation.events,
                                         "real_models": False, "real_maps": False, "personal_phone": False})
    except BaseException as error:
        evaluation.save("summary.json", {"status": "failed", "error": str(error), "events": evaluation.events})
        try:
            evaluation.capture("failure")
        except Exception:
            pass
        raise


if __name__ == "__main__":
    main()
