//go:build windows

package main

import (
	"encoding/binary"
	"errors"
	"html"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"unicode/utf16"

	"golang.org/x/sys/windows"
)

// Start with Windows.
//
// Dash needs admin rights, and Windows does not start such programs from the Run key, so a
// Task Scheduler task with "run with highest privileges" starts it at logon, without a UAC prompt.
// Because that task runs elevated silently, it must never point at a file other programs can
// replace (Dash.exe usually sits in Downloads): the task starts a copy in Program Files, where
// only administrators can write. That copy updates itself like any other Dash.exe.

const trayArg = "--tray" // start hidden in the tray

func installDir() string {
	pf, err := windows.KnownFolderPath(windows.FOLDERID_ProgramFiles, 0)
	if err != nil || pf == "" {
		pf = `C:\Program Files`
	}
	return filepath.Join(pf, "Dash")
}

func installedExe() string { return filepath.Join(installDir(), "Dash.exe") }

func installedVerFile() string { return filepath.Join(dataRoot(), "installed-version") }

func userSID() (*windows.SID, error) {
	u, err := windows.GetCurrentProcessToken().GetTokenUser()
	if err != nil {
		return nil, err
	}
	return u.User.Sid, nil
}

func taskName() string {
	sid, err := userSID()
	if err != nil {
		return `\Dash\Autostart`
	}
	return `\Dash\Autostart-` + sid.String()
}

func schtasks(args ...string) error {
	cmd := exec.Command(system32("schtasks.exe"), args...)
	hideWindow(cmd)
	return cmd.Run()
}

func samePath(a, b string) bool {
	clean := func(p string) string {
		p = strings.TrimPrefix(p, `\\?\`)
		if r, err := filepath.EvalSymlinks(p); err == nil {
			p = r
		}
		return strings.ToLower(filepath.Clean(p))
	}
	return clean(a) == clean(b)
}

// AutostartEnabled reports whether the logon task exists.
func AutostartEnabled() bool { return schtasks("/Query", "/TN", taskName()) == nil }

func SetAutostart(on bool) error {
	if !on {
		if err := schtasks("/Delete", "/TN", taskName(), "/F"); err != nil && AutostartEnabled() {
			return errors.New("не удалось удалить задачу автозапуска")
		}
		removeInstalled()
		return nil
	}
	exe, err := installSelf()
	if err != nil {
		return errors.New("не удалось скопировать Dash в Program Files: " + err.Error())
	}
	sid, err := userSID()
	if err != nil {
		return err
	}
	account, domain, _, _ := sid.LookupAccount("")
	logonUser := account
	if domain != "" {
		logonUser = domain + `\` + account
	}
	xml := `<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <RegistrationInfo><Description>Dash: запуск VPN-клиента при входе в Windows</Description></RegistrationInfo>
  <Triggers>
    <LogonTrigger><Enabled>true</Enabled><UserId>` + html.EscapeString(logonUser) + `</UserId><Delay>PT5S</Delay></LogonTrigger>
  </Triggers>
  <Principals>
    <Principal id="Author"><UserId>` + sid.String() + `</UserId><LogonType>InteractiveToken</LogonType><RunLevel>HighestAvailable</RunLevel></Principal>
  </Principals>
  <Settings>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
    <AllowHardTerminate>true</AllowHardTerminate>
    <StartWhenAvailable>false</StartWhenAvailable>
    <RunOnlyIfNetworkAvailable>false</RunOnlyIfNetworkAvailable>
    <IdleSettings><StopOnIdleEnd>false</StopOnIdleEnd><RestartOnIdle>false</RestartOnIdle></IdleSettings>
    <AllowStartOnDemand>true</AllowStartOnDemand>
    <Enabled>true</Enabled>
    <Hidden>false</Hidden>
    <RunOnlyIfIdle>false</RunOnlyIfIdle>
    <WakeToRun>false</WakeToRun>
    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
    <Priority>4</Priority>
  </Settings>
  <Actions Context="Author">
    <Exec><Command>` + html.EscapeString(exe) + `</Command><Arguments>` + trayArg + `</Arguments></Exec>
  </Actions>
</Task>
`
	// schtasks wants the XML as UTF-16 with a BOM; the file sits in the admin-only folder
	u := utf16.Encode([]rune(xml))
	b := make([]byte, 2+2*len(u))
	binary.LittleEndian.PutUint16(b, 0xFEFF)
	for i, c := range u {
		binary.LittleEndian.PutUint16(b[2+2*i:], c)
	}
	path := filepath.Join(dataRoot(), "autostart.xml")
	if err := os.WriteFile(path, b, 0600); err != nil {
		return err
	}
	defer os.Remove(path)
	if err := schtasks("/Create", "/TN", taskName(), "/XML", path, "/F"); err != nil {
		return errors.New("Планировщик заданий не принял задачу автозапуска")
	}
	return nil
}

// installSelf copies the running Dash.exe to Program Files (unless it already runs from there).
func installSelf() (string, error) {
	dst := installedExe()
	self, err := os.Executable()
	if err != nil {
		return "", err
	}
	if samePath(self, dst) {
		_ = os.WriteFile(installedVerFile(), []byte(Version), 0600)
		return dst, nil
	}
	dir := installDir()
	if isReparse(dir) {
		return "", errors.New(dir + " — ссылка, а не папка")
	}
	if err := os.MkdirAll(dir, 0755); err != nil {
		return "", err
	}
	tmp := dst + ".new"
	if err := copyFile(self, tmp); err != nil {
		_ = os.Remove(tmp)
		return "", err
	}
	if err := os.Rename(tmp, dst); err != nil {
		_ = os.Remove(tmp)
		return "", err
	}
	_ = os.WriteFile(installedVerFile(), []byte(Version), 0600)
	return dst, nil
}

func removeInstalled() {
	self, _ := os.Executable()
	if samePath(self, installedExe()) {
		return // running from there: keep it
	}
	_ = os.Remove(installedExe())
	_ = os.Remove(installedExe() + ".old")
	_ = os.Remove(installDir()) // only if empty
	_ = os.Remove(installedVerFile())
}

// syncInstalled keeps the Program Files copy no older than the Dash.exe the user starts by hand,
// and remembers the version after the copy updated itself.
func syncInstalled() {
	if _, err := os.Stat(installedExe()); err != nil {
		return
	}
	self, err := os.Executable()
	if err != nil {
		return
	}
	if samePath(self, installedExe()) {
		_ = os.WriteFile(installedVerFile(), []byte(Version), 0600)
		return
	}
	have := "0"
	if b, err := os.ReadFile(installedVerFile()); err == nil {
		have = strings.TrimSpace(string(b))
	}
	if newer(Version, have) {
		_, _ = installSelf()
	}
}
