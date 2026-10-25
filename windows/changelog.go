package main

import "sort"

// changelog: what's new, shown once on the main page after an update. Add a line whenever a version
// ships something worth telling the user about; short bullet points, impersonal wording.
var changelog = map[string][]string{
	"1.5.0": {
		"Закрытие окна прячет Dash в трей, VPN продолжает работать",
		"Значок в трее: клик открывает окно, правый клик — подключить, отключить или выйти",
	},
	"1.5.1": {
		"Пинг до 300 мс теперь считается хорошим и подсвечивается зелёным",
	},
	"1.5.2": {
		"Защита: данные и ядра Dash лежат в папке, куда могут писать только администраторы, ядра сверяются по SHA-256",
	},
	"1.5.3": {
		"Настройки → Запуск: Dash может стартовать вместе с Windows и сразу прятаться в трей",
		"Настройки разложены по разделам, журнал свёрнут",
	},
	"1.6.0": {
		"Автовыбор учитывает реальную скорость: после подключения и после сна Dash коротко замеряет скорость трёх серверов с лучшим пингом и переходит на заметно более быстрый",
		"Если сервер отвечает на пинг, но не пропускает трафик, Dash уходит на другой",
		"Новый экран настроек: разделы с иконками и поиск",
	},
}

// whatsNew lists the entries newer than seen up to the running version, oldest first.
// Settings from before 1.6.0 have no seen version: they are 1.5.3 or older.
func whatsNew(seen string) []string {
	if seen == "" {
		seen = "1.5.3"
	}
	var vers []string
	for v := range changelog {
		if newer(v, seen) && !newer(v, Version) {
			vers = append(vers, v)
		}
	}
	sort.Slice(vers, func(i, j int) bool { return newer(vers[j], vers[i]) })
	var out []string
	for _, v := range vers {
		out = append(out, changelog[v]...)
	}
	return out
}
