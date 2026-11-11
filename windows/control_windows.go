//go:build windows

package main

import (
	"encoding/json"
	"strings"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

// Control pipe: other programs of the same user (for example the Island app) can turn the VPN on
// and off and read its state, nothing else. One line in ("state", "on", "off", "toggle"), one JSON
// line out. Only the signed-in user's own SID (and SYSTEM / Administrators) may connect, remote
// clients are rejected, and the user may not create pipe instances of their own (no
// FILE_CREATE_PIPE_INSTANCE), so a non-admin program cannot pose as Dash.
const controlPipe = `\\.\pipe\Dash-control`

type controlState struct {
	State  string `json:"state"`
	Server string `json:"server"`
	Phase  string `json:"phase"`
	Error  string `json:"error"`
}

func controlReply(c *Core, cmd string) []byte {
	switch cmd {
	case "on":
		c.Connect()
	case "off":
		c.Disconnect("Отключено из другой программы")
	case "toggle":
		if c.View().State == StOff {
			c.Connect()
		} else {
			c.Disconnect("Отключено из другой программы")
		}
	case "state":
	default:
		b, _ := json.Marshal(map[string]string{"error": "unknown command"})
		return append(b, '\n')
	}
	v := c.View()
	b, _ := json.Marshal(controlState{State: v.State, Server: v.Server, Phase: v.Phase, Error: v.Error})
	return append(b, '\n')
}

func controlSA() (*windows.SecurityAttributes, error) {
	sid, err := userSID()
	if err != nil {
		return nil, err
	}
	// 0x12019b = FILE_GENERIC_READ | FILE_GENERIC_WRITE without FILE_CREATE_PIPE_INSTANCE
	sd, err := windows.SecurityDescriptorFromString("D:P(A;;GA;;;SY)(A;;GA;;;BA)(A;;0x12019b;;;" + sid.String() + ")")
	if err != nil {
		return nil, err
	}
	return &windows.SecurityAttributes{Length: uint32(unsafe.Sizeof(windows.SecurityAttributes{})), SecurityDescriptor: sd}, nil
}

// serveControl runs for the life of Dash; failures only go to the log.
func serveControl(c *Core, log *Log) {
	sa, err := controlSA()
	if err != nil {
		log.Add("Управление из других программ недоступно: " + err.Error())
		return
	}
	name, _ := windows.UTF16PtrFromString(controlPipe)
	first := true
	for {
		mode := uint32(windows.PIPE_ACCESS_DUPLEX)
		if first {
			mode |= windows.FILE_FLAG_FIRST_PIPE_INSTANCE
		}
		h, err := windows.CreateNamedPipe(name, mode,
			windows.PIPE_TYPE_BYTE|windows.PIPE_READMODE_BYTE|windows.PIPE_WAIT|windows.PIPE_REJECT_REMOTE_CLIENTS,
			windows.PIPE_UNLIMITED_INSTANCES, 512, 512, 0, sa)
		if err != nil {
			if first {
				log.Add("Управление из других программ недоступно: " + err.Error())
				return
			}
			time.Sleep(time.Second)
			continue
		}
		first = false
		if err := windows.ConnectNamedPipe(h, nil); err != nil && err != windows.ERROR_PIPE_CONNECTED {
			windows.CloseHandle(h)
			continue
		}
		go handleControl(c, h)
	}
}

func handleControl(c *Core, h windows.Handle) {
	defer windows.CloseHandle(h)
	defer windows.DisconnectNamedPipe(h)
	buf := make([]byte, 64)
	var line []byte
	for len(line) < 64 {
		var n uint32
		if err := windows.ReadFile(h, buf, &n, nil); err != nil || n == 0 {
			break
		}
		line = append(line, buf[:n]...)
		if strings.ContainsRune(string(line), '\n') {
			break
		}
	}
	cmd := strings.ToLower(strings.TrimSpace(strings.SplitN(string(line), "\n", 2)[0]))
	out := controlReply(c, cmd)
	var n uint32
	_ = windows.WriteFile(h, out, &n, nil)
	_ = windows.FlushFileBuffers(h)
}
