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

// RemindDay is 3, 2 or 1 while that many days are left (rounded up) for a reminder, else 0.
func (s *SubInfo) RemindDay(now time.Time) int {
	if s.Expire == 0 || s.Over(now) {
		return 0
	}
	if d := s.DaysLeft(now); d >= 1 && d <= 3 {
		return d
	}
	return 0
}

// days: "1 день", "2 дня", "5 дней".
func days(n int) string {
	m, k := n%100, n%10
	w := "дней"
	switch {
	case m >= 11 && m <= 14:
	case k == 1:
		w = "день"
	case k >= 2 && k <= 4:
		w = "дня"
	}
	return fmt.Sprintf("%d %s", n, w)
}

// remind shows one notification each at 3, 2 and 1 days before a subscription ends.
// The key holds the end date, so a renewed subscription reminds again.
func (c *Core) remind(now time.Time) {
	notify := c.Notify
	if notify == nil {
		return
	}
	d := c.prefs.Get()
	for _, u := range d.Subs {
		si := ParseSubInfo(d.Infos[u])
		if si == nil {
			continue
		}
		n := si.RemindDay(now)
		if n == 0 {
			continue
		}
		key := fmt.Sprintf("%d:%d", si.Expire, n)
		if d.Reminded[u] == key {
			continue
		}
		c.prefs.Update(func(p *PrefsData) {
			if p.Reminded == nil {
				p.Reminded = map[string]string{}
			}
			p.Reminded[u] = key
		})
		notify(c.prefs.SubLabel(u)+" — осталось "+days(n), si.Text(now))
	}
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

func gb(b int64) string {
	g := float64(b) / (1 << 30)
	if g >= 10 {
		return strconv.FormatInt(int64(math.Round(g)), 10)
	}
	return strings.TrimSuffix(strconv.FormatFloat(g, 'f', 1, 64), ".0")
}

// Ring is what the subscriptions list draws: the arc is the traffic spent, the label the days left.
// State: ok, low, over or unlimited (no traffic limit; no end date is shown as ∞). nil without data.
func Ring(h string, now time.Time) map[string]any {
	s := ParseSubInfo(h)
	if s == nil {
		return nil
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
	return map[string]any{"state": state, "label": label, "part": part, "text": s.Text(now)}
}
