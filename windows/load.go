package main

import (
	"fmt"
	"time"
)

// frozenNow: servers that failed the load check recently.
func (c *Core) frozenNow() map[string]bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	out := map[string]bool{}
	for t, until := range c.frozen {
		if time.Now().Before(until) {
			out[t] = true
		} else {
			delete(c.frozen, t)
		}
	}
	return out
}

func (c *Core) freeze(tag string) {
	c.mu.Lock()
	if c.frozen == nil {
		c.frozen = map[string]time.Time{}
	}
	c.frozen[tag] = time.Now().Add(frozenFor)
	c.mu.Unlock()
}

func (c *Core) clearFrozen() {
	c.mu.Lock()
	c.frozen, c.loadFails, c.lastLoad = nil, 0, time.Time{}
	c.mu.Unlock()
}

// maybeLoadCheck is called by the watchdog after the server in use answered (a manual pin is left alone).
// Every few minutes, and right after the server changes, a small file has to come through it in full. After two
// failures in a row the server is left out for a while and traffic moves to the best server that passes the
// same check.
func (c *Core) maybeLoadCheck(g int, tag string) {
	c.mu.Lock()
	port := c.speedPort
	ok := c.gen == g && c.state == StOn && !c.loadBusy && !c.speedBusy && port > 0 &&
		(tag != c.lastLoadTag || c.loadFails > 0 || time.Since(c.lastLoad) > loadEvery)
	if ok {
		c.loadBusy, c.lastLoad, c.lastLoadTag = true, time.Now(), tag
	}
	group := c.activeGroup
	c.mu.Unlock()
	if ok {
		go c.loadCheck(g, port, group, tag)
	}
}

func (c *Core) loadCheck(g, port int, group, tag string) {
	defer func() {
		c.mu.Lock()
		c.loadBusy = false
		c.mu.Unlock()
	}()
	cl := c.cl()
	if cl == nil {
		return
	}
	if LoadOK(port, tag, cl.secret) {
		c.mu.Lock()
		c.loadFails = 0
		c.mu.Unlock()
		return
	}
	if !c.current(g) {
		return
	}
	c.mu.Lock()
	c.loadFails++
	fails := c.loadFails
	if fails >= 2 {
		c.loadFails = 0
	}
	c.mu.Unlock()
	name := c.nameOf(tag)
	c.log.Add(fmt.Sprintf("Сервер %s отвечает, но не пропускает загрузку (%d)", name, fails))
	if fails < 2 {
		return
	}
	c.freeze(tag)
	best := ""
	for _, t := range spares(cl.Members(group), cl.Delays(), c.frozenNow()) {
		if !c.current(g) {
			return
		}
		if LoadOK(port, t, cl.secret) {
			best = t
			break
		}
		c.freeze(t)
		c.log.Add(fmt.Sprintf("Сервер %s тоже не пропускает загрузку", c.nameOf(t)))
	}
	c.mu.Lock()
	inUse := c.autoTag
	still := c.gen == g && c.state == StOn && !c.switching && c.activeGroup == group
	c.mu.Unlock()
	if inUse == "" {
		inUse = cl.Now(group)
	}
	if !still || c.pinnedTag() != "" || inUse != tag {
		return
	}
	if best == "" {
		c.showBanner("Сервер "+name+" почти не пропускает трафик, замены не нашлось", 2)
		return
	}
	if cl.Select(Selector, best) != nil {
		return
	}
	c.mu.Lock()
	c.autoTag, c.deadCount = best, 0
	c.mu.Unlock()
	cl.CloseAll() // connections stuck on the stalled server: reopen through the new one
	c.refreshStatus()
	c.showBanner(fmt.Sprintf("Сервер %s перестал пропускать трафик — переключено на %s", name, c.nameOf(best)), 2)
}
