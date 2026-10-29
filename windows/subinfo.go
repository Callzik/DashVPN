package main

import (
	"fmt"
	"math"
	"strconv"
	"strings"
	"time"
)

// SubInfo is traffic and expiry from the panel's subscription-userinfo header
// ("upload=1; download=2; total=3; expire=1700000000"). Zero total means unlimited, zero expire means forever.
type SubInfo struct {
	Used, Total int64 // bytes
	Expire      int64 // unix seconds, 0 = no end date
}

// ParseSubInfo returns nil when the header is missing or says nothing useful.
func ParseSubInfo(h string) *SubInfo {
	s := &SubInfo{}
	any := false
	for _, part := range strings.Split(h, ";") {
		k, v, ok := strings.Cut(part, "=")
		if !ok {
			continue
		}
		f, err := strconv.ParseFloat(strings.TrimSpace(v), 64)
		if err != nil {
			continue
		}
		n := int64(math.Max(f, 0))
		switch strings.ToLower(strings.TrimSpace(k)) {
		case "upload", "download":
			s.Used += n
		case "total":
			s.Total = n
		case "expire":
			s.Expire = n
		default:
			continue
		}
		any = true
	}
	if !any || (s.Total == 0 && s.Expire == 0) {
		return nil
	}
	return s
}

// DaysLeft is whole days left, rounded up; -1 when there is no end date.
func (s *SubInfo) DaysLeft(now time.Time) int {
	if s.Expire == 0 {
		return -1
	}
	left := time.Unix(s.Expire, 0).Sub(now)
	if left <= 0 {
		return 0
	}
	return int((left + 24*time.Hour - time.Millisecond) / (24 * time.Hour))
}

// UsedPart is the share of the traffic spent, 0..1; 0 when unlimited.
func (s *SubInfo) UsedPart() float64 {
	if s.Total <= 0 {
		return 0
	}
	return math.Min(1, float64(s.Used)/float64(s.Total))
}

func (s *SubInfo) Over(now time.Time) bool {
	return (s.Expire != 0 && !now.Before(time.Unix(s.Expire, 0))) || (s.Total > 0 && s.Used >= s.Total)
}

// Low: less than 3 days or 10% of the traffic left.
func (s *SubInfo) Low(now time.Time) bool {
	if s.Over(now) {
		return false
	}
	d := s.DaysLeft(now)
	return (d >= 0 && d < 3) || (s.Total > 0 && s.Total-s.Used < s.Total/10)
}

// Text: "12 / 50 ГБ · 38 дн", "∞ · 120 дн", "закончилась".
func (s *SubInfo) Text(now time.Time) string {
	if s.Over(now) {
		return "закончилась"
	}
	t := "∞"
	if s.Total > 0 {
		t = gb(s.Used) + " / " + gb(s.Total) + " ГБ"
	}
	if d := s.DaysLeft(now); d >= 0 {
		t += fmt.Sprintf(" · %d дн", d)
	}
	return t
}

// rank: lower is more urgent: over, then low, then by days left.
func (s *SubInfo) rank(now time.Time) int64 {
	switch {
	case s.Over(now):
		return -2
	case s.Low(now):
		return -1
	}
	if d := s.DaysLeft(now); d >= 0 {
		return int64(d)
	}
	return math.MaxInt64
}

// MostUrgent is the subscription that needs attention first, nil if none has data.
func MostUrgent(list []*SubInfo, now time.Time) *SubInfo {
	var best *SubInfo
	for _, s := range list {
		if s != nil && (best == nil || s.rank(now) < best.rank(now)) {
			best = s
		}
	}
	return best
}

func gb(b int64) string {
	g := float64(b) / (1 << 30)
	if g >= 10 || g == math.Floor(g) {
		return strconv.FormatInt(int64(math.Round(g)), 10)
	}
	return strconv.FormatFloat(g, 'f', 1, 64)
}

// SubRing is what the servers screen draws: the arc is the traffic spent, the label the days left.
// State: ok, low, over or unlimited (no traffic limit; no end date is shown as ∞).
func (c *Core) SubRing(now time.Time) map[string]any {
	d := c.prefs.Get()
	var infos []*SubInfo
	var lines []string
	for _, u := range d.Subs {
		si := ParseSubInfo(d.Infos[u])
		if si == nil {
			continue
		}
		infos = append(infos, si)
		lines = append(lines, c.prefs.SubLabel(u)+" · "+si.Text(now))
	}
	s := MostUrgent(infos, now)
	if s == nil {
		return nil
	}
	if len(lines) == 1 {
		lines[0] = s.Text(now)
	}
	state, label := "ok", "∞"
	if n := s.DaysLeft(now); n >= 0 {
		label = strconv.Itoa(min(n, 999))
	}
	part := s.UsedPart()
	switch {
	case s.Over(now):
		state, label, part = "over", "!", 1
	case s.Low(now):
		state = "low"
	case s.Total <= 0:
		state = "unlimited"
	}
	return map[string]any{"state": state, "label": label, "part": part, "lines": lines}
}
