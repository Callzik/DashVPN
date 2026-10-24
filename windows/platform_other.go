//go:build !windows

package main

import (
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
)

const (
	singBoxExe = "sing-box"
	xrayExe    = "xray"
)

func bypassProcs() []string { return nil }

func init() {
	if u := os.Getenv("DASH_TEST_URL"); u != "" {
		TestURL = u
	}
	if u := os.Getenv("DASH_SPEED_URL"); u != "" {
		SpeedURL = u
	}
	if u := os.Getenv("DASH_LOAD_URL"); u != "" {
		LoadURL = u
	}
	if p := os.Getenv("DASH_TEST_MIXED"); p != "" {
		testMixedPort, _ = strconv.Atoi(p)
	}
}

func hideWindow(cmd *exec.Cmd) {}

func afterStart(cmd *exec.Cmd) {}

func killProc(cmd *exec.Cmd) {
	if cmd != nil && cmd.Process != nil {
		_ = cmd.Process.Kill()
	}
}

func machineID() string { return "" }
func osVersion() string { return "linux" }
func deviceModel() string {
	h, _ := os.Hostname()
	return h
}
func dataRoot() string {
	if d := os.Getenv("DASH_DIR"); d != "" {
		return d
	}
	return filepath.Join(os.TempDir(), "dash-test")
}

func verifyCore(bin string) (func(), error) { return func() {}, nil }
func runningApps() []string                 { return nil }
func swapExe(newExe string) (string, error) { return newExe, nil }
func cleanupOldExe()                        {}
func waitPid(pid int)                       {}

const trayArg = "--tray"

func AutostartEnabled() bool { return false }
func SetAutostart(on bool) error {
	return errors.New("автозапуск есть только в Windows")
}
func syncInstalled()       {}
func installedExe() string { return "" }
