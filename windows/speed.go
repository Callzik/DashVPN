package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"time"
)

// SpeedURL is the test download for the speed check; a var so the Linux test build can use a local file.
var SpeedURL = "https://speed.cloudflare.com/__down?bytes=2000000"

// LoadURL is the load check's file: a server can answer the watchdog's tiny ping request and still stall every
// real download (censors freeze such connections after the first 16–20 KB), so a small file has to arrive in full.
var LoadURL = "https://speed.cloudflare.com/__down?bytes=131072"

const (
	speedCandidates = 3               // best servers by ping that get a test download
	speedWindow     = 4 * time.Second // a download stops after this long even if unfinished
	speedEvery      = 3 * time.Minute // at most one speed check per this period

	loadMin    = 100 * 1024       // bytes a load check must get
	loadWindow = 10 * time.Second // a load check stops after this long
	loadEvery  = 5 * time.Minute  // a load check of the server in use at least this often
	frozenFor  = 15 * time.Minute // a server that failed the load check is left out this long
)

// MeasureSpeed downloads SpeedURL through one server and returns Mbit/s, 0 on failure.
// The core's speed-in proxy sends each login (= server tag) to that server only.
func MeasureSpeed(port int, tag, secret string) float64 {
	_, n, el := download(SpeedURL, speedWindow, port, tag, secret)
	if n < 64*1024 || el <= 0 {
		return 0
	}
	return float64(n) * 8 / el / 1e6
}

// LoadOK: the load check file comes through one server in full.
func LoadOK(port int, tag, secret string) bool {
	all, _, _ := download(LoadURL, loadWindow, port, tag, secret)
	return all >= loadMin
}

// download reads u through one server for up to window after the first data and returns all bytes,
// the bytes after the first chunk and the seconds since the first chunk.
func download(u string, window time.Duration, port int, tag, secret string) (int64, int64, float64) {
	pu := &url.URL{Scheme: "http", User: url.UserPassword(tag, secret), Host: "127.0.0.1:" + strconv.Itoa(port)}
	tr := &http.Transport{Proxy: http.ProxyURL(pu), DisableKeepAlives: true,
		TLSHandshakeTimeout: 6 * time.Second, ResponseHeaderTimeout: 6 * time.Second}
	defer tr.CloseIdleConnections()
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, "GET", u, nil)
	if err != nil {
		return 0, 0, 0
	}
	resp, err := (&http.Client{Transport: tr}).Do(req)
	if err != nil {
		return 0, 0, 0
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return 0, 0, 0
	}
	// the clock starts at the first data, so the server's ping does not count against its speed
	buf := make([]byte, 32*1024)
	var start time.Time
	var all, n int64
	for {
		k, err := resp.Body.Read(buf)
		if k > 0 {
			all += int64(k)
			if start.IsZero() {
				start = time.Now()
			} else {
				n += int64(k)
			}
		}
		if err != nil || !start.IsZero() && time.Since(start) > window {
			break
		}
	}
	if start.IsZero() {
		return 0, 0, 0
	}
	return all, n, time.Since(start).Seconds()
}

// Delays: last measured delay of every outbound in one call (tag → ms, failed and untested left out).
func (c *Clash) Delays() map[string]int {
	out := map[string]int{}
	b, err := c.call("GET", "/proxies", nil, 4*time.Second)
	if err != nil {
		return out
	}
	var o map[string]any
	if json.Unmarshal(b, &o) != nil {
		return out
	}
	for tag, p := range m(o["proxies"]) {
		h := l(m(p)["history"])
		if len(h) == 0 {
			continue
		}
		if d := numOf(m(h[len(h)-1])["delay"]); d > 0 {
			out[tag] = d
		}
	}
	return out
}

// speedPicks: the best few members of a group by ping that are not in skip, plus the current one.
func speedPicks(members []string, delays map[string]int, cur string, skip map[string]bool) []string {
	alive := spares(members, delays, skip)
	if cur != "" {
		for _, t := range alive {
			if t == cur {
				return alive
			}
		}
		alive = append(alive, cur)
	}
	return alive
}

// spares: the best few members of a group by ping that answer and are not in skip.
func spares(members []string, delays map[string]int, skip map[string]bool) []string {
	var alive []string
	for _, t := range members {
		if delays[t] > 0 && !skip[t] {
			alive = append(alive, t)
		}
	}
	sort.SliceStable(alive, func(i, j int) bool { return delays[alive[i]] < delays[alive[j]] })
	if len(alive) > speedCandidates {
		alive = alive[:speedCandidates]
	}
	return alive
}

// fasterPick: the fastest result when it clearly beats the current server (1.5 times and 3 Mbit/s), else "".
func fasterPick(speeds map[string]float64, cur string) string {
	best, bs := "", 0.0
	for t, s := range speeds {
		if s > bs || s == bs && t < best {
			best, bs = t, s
		}
	}
	cs := speeds[cur]
	if best == "" || best == cur || bs < cs*1.5 || bs-cs < 3 {
		return ""
	}
	return best
}

func mbit(v float64) string {
	if v <= 0 {
		return "не скачалось"
	}
	if v < 10 {
		return fmt.Sprintf("%.1f Мбит/с", v)
	}
	return fmt.Sprintf("%.0f Мбит/с", v)
}

// speedCheck: the lowest ping is not always the fastest (an overloaded server answers quickly but downloads
// slowly). Downloads a test file through the best few servers by ping, one after another, and moves to one
// that is clearly faster than the current. Runs after connecting and after waking from sleep.
func (c *Core) speedCheck(g int) {
	c.mu.Lock()
	port := c.speedPort
	ok := c.gen == g && c.state == StOn && !c.switching && !c.speedBusy && c.autoTag == "" &&
		port > 0 && time.Since(c.lastSpeed) > speedEvery
	if ok {
		c.speedBusy, c.lastSpeed = true, time.Now()
	}
	group := c.activeGroup
	c.mu.Unlock()
	if !ok {
		return
	}
	defer func() {
		c.mu.Lock()
		c.speedBusy = false
		c.mu.Unlock()
	}()
	cl := c.cl()
	if cl == nil || c.pinnedTag() != "" {
		return
	}
	cur := cl.Now(group)
	picks := speedPicks(cl.Members(group), cl.Delays(), cur, c.frozenNow())
	if len(picks) < 2 {
		return
	}
	speeds := map[string]float64{}
	for _, t := range picks {
		if !c.current(g) {
			return
		}
		speeds[t] = MeasureSpeed(port, t, cl.secret)
		c.log.Add(fmt.Sprintf("Скорость: %s — %s", c.nameOf(t), mbit(speeds[t])))
	}
	best := fasterPick(speeds, cur)
	if best == "" {
		return
	}
	c.mu.Lock()
	still := c.gen == g && c.state == StOn && !c.switching && c.autoTag == "" && c.activeGroup == group
	c.mu.Unlock()
	if !still || c.pinnedTag() != "" || cl.Now(group) != cur {
		return
	}
	if cl.Select(Selector, best) != nil {
		return
	}
	c.mu.Lock()
	c.autoTag, c.deadCount = best, 0
	c.mu.Unlock()
	c.refreshStatus()
	c.showBanner(fmt.Sprintf("Выбран более быстрый сервер: %s · %s", c.nameOf(best), mbit(speeds[best])), 1)
}

// laterSpeedCheck runs the speed check a few seconds from now, once the first pings have settled.
func (c *Core) laterSpeedCheck(g int) {
	time.AfterFunc(6*time.Second, func() { c.speedCheck(g) })
}

// clearAuto drops the speed check's choice and hands traffic back to the group.
func (c *Core) clearAuto(cl *Clash, group string) bool {
	c.mu.Lock()
	had := c.autoTag != ""
	c.autoTag = ""
	c.mu.Unlock()
	if had && cl != nil {
		_ = cl.Select(Selector, group)
	}
	return had
}

func (c *Core) nameOf(tag string) string {
	c.mu.Lock()
	defer c.mu.Unlock()
	if s, ok := c.byTag[tag]; ok {
		return s.Name
	}
	return tag
}
