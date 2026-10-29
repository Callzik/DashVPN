package app.dash;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What's-new entries shown once after an update. Add a line here whenever a version ships
 * something worth telling the user about; MainActivity shows every entry newer than the
 * version they last saw, then remembers the current one.
 */
public final class Changelog {
    private Changelog() {}

    // versionCode (AndroidManifest) -> short bullet points, impersonal wording.
    private static final Map<Integer, String[]> ENTRIES = new LinkedHashMap<>();
    static {
        ENTRIES.put(30, new String[]{
                "После обновления показывается список изменений",
                "В настройках появилась кнопка «Проверить обновление»",
        });
        ENTRIES.put(31, new String[]{
                "Пинг до 300 мс теперь считается хорошим и подсвечивается зелёным",
        });
        ENTRIES.put(32, new String[]{
                "Конструктор главного экрана: плашки можно прятать и переставлять (Настройки → Главный экран или долгое нажатие на плашку)",
                "Новые плашки: скорость, трафик за подключение, внешний IP с флагом страны",
        });
        ENTRIES.put(33, new String[]{
                "Исправлены уязвимости: другие приложения больше не могут запускать экраны через Dash и выключать VPN через виджет",
        });
        ENTRIES.put(34, new String[]{
                "VPN для точки доступа: устройства, подключённые к телефону, могут ходить через VPN по прокси (Настройки → Раздача)",
        });
        ENTRIES.put(35, new String[]{
                "Автовыбор учитывает реальную скорость: после подключения и при смене сети Dash коротко замеряет скорость трёх серверов с лучшим пингом и переходит на заметно более быстрый",
        });
        ENTRIES.put(36, new String[]{
                "Уведомление-«остров»: сервер, время подключения и скорость, кнопки «Пауза», «Переключить» и «Отключить». На Android 16 оно видно в строке состояния и в Now Bar на Samsung",
                "Пауза выключает VPN на 5 минут, потом он включается сам",
                "Если сервер отвечает на пинг, но не пропускает трафик, Dash уходит на другой",
                "Новый экран настроек: разделы с иконками и поиск",
        });
        ENTRIES.put(37, new String[]{
                "Настройки → Остров и Now Bar: показывает, пускает ли телефон уведомление Dash в строку состояния, и открывает нужные настройки",
        });
        ENTRIES.put(38, new String[]{
                "В таблетке в строке состояния и в Now Bar видно, сколько VPN уже включён",
        });
        ENTRIES.put(39, new String[]{
                "Samsung: уведомление VPN попадает в Now Bar и таблетку в строке состояния без настроек для разработчиков",
        });
        ENTRIES.put(40, new String[]{
                "Новый экран серверов: флаги стран, цветной пинг с полосками сигнала, «Авто» на отдельной карточке сверху",
                "Серверы разделены на основные, резервные (LTE) и отключённые, видно, какой сервер работает сейчас",
                "Поиск по серверам, когда их больше пяти",
        });
        ENTRIES.put(41, new String[]{
                "Экран серверов стал проще: без лишних подписей, пинг цифрой с полосками, галочка у выбранного сервера и точка у того, что работает сейчас",
        });
        ENTRIES.put(42, new String[]{
                "Обновления приходят, даже если GitHub заблокирован у провайдера: при включённом VPN проверка и загрузка идут через сервер, есть запасной адрес",
                "Если проверить обновления не удалось, Dash об этом пишет, а не показывает «последняя версия»",
                "Если ядро падает, в журнале остаётся причина сбоя, а не только хвост длинного отчёта",
        });
        ENTRIES.put(43, new String[]{
                "Флаг страны показывается в плитке, даже если в названии сервера он стоит не в начале",
        });
        ENTRIES.put(44, new String[]{
                "Сервер можно перевести в обычные, в LTE или в обе группы сразу: долгое нажатие на сервере",
                "Настройки стали проще: без лишних подписей и пояснений",
        });
        ENTRIES.put(45, new String[]{
                "Космический главный экран: при подключении звёзды уходят в прыжок, после — плывут, за кнопкой светится туманность",
                "Таймер крупно, под ним скорость загрузки и отдачи; в карточке сервера флаг и пинг",
        });
        ENTRIES.put(46, new String[]{
                "Новое окно «Что нового»: после обновления видно, что появилось",
                "Плавный прыжок: звёзды при подключении летят ровно, без скачков",
        });
        ENTRIES.put(47, new String[]{
                "Плоская кнопка: при подключении снова ровный фиолетовый круг со свечением, без объёма",
        });
    }

    /** Entries for every version newer than {@code fromCode} up to and including {@code toCode}, oldest first. */
    static java.util.List<String> since(int fromCode, int toCode) {
        java.util.List<Integer> codes = new java.util.ArrayList<>(ENTRIES.keySet());
        java.util.Collections.sort(codes);
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int code : codes) {
            if (code > fromCode && code <= toCode) {
                for (String line : ENTRIES.get(code)) out.add(line);
            }
        }
        return out;
    }
}
