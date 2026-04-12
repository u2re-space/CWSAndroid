# Manual test scenarios

## Reverse WS diagnostics: ws/wss candidate rotation (Android + AirPad)

### Scenario 1 — Проверка последовательности `wss -> ws`

Goal:
- Verify the same candidate sequence and unified status format appear in Android Gateway status / logs and AirPad logs.

Preconditions:
- На машине есть два тестовых WS URL:
  - `wss://...` недоступный для подключения endpoint (или порт закрыт/сервис недоступен).
  - `ws://...` доступный endpoint.
- На мобильном приложении в `Gateway URL` указан список через запятую, например:
  - `wss://10.0.0.2:8443,ws://10.0.0.2:8080`
- На AirPad включён лог и открыт Overlay (`Логи` / `log overlay`).

Steps:
1. Перезапустить reverse-клиент Android (или перезайти в приложение).
2. На старте проверить в UI (Settings → Gateway block) строку из `daemonLines`:
   - `ws-state: candidate=1/2 ...`
   - `ws-state: last_error=...` (после первой ошибки).
3. В логах Android ожидать:
   - `[ws-state] event=connecting ... candidate=1/2 ... scheme=wss ...`
   - затем `event=disconnected/failure` с `candidate=1/2`.
   - затем `event=connecting ... candidate=2/2 ... scheme=ws ...`
   - затем `event=connected ...`.
4. В логе AirPad ожидать аналогичный набор в формате:
   - `[ws-state] event=connecting ... candidate=1/2 ...`
   - `[ws-state] event=connect-failed ... candidate=1/2 ...`
   - `[ws-state] event=connecting ... candidate=2/2 ...`
   - `[ws-state] event=connected ...`

### Scenario 2 — Проверка возврата на следующий кандидат после падения

Goal:
- Проверить, что после падения активного канала происходит переход на следующий кандидат, а затем к нему возвращается по циклу.

Steps:
1. Оставить список из двух кандидатов:
   - `wss://...` (недоступен)
   - `ws://...` (доступен)
2. Дождаться подключения на `ws://` кандидате (`candidate=2/2`).
3. Остановить/выключить сервис на активном `ws://` кандидате на 10–20 секунд, чтобы вызвать закрытие.
4. Проверить в логе:
   - Android: `event=failure`/`event=disconnected`, затем `event=connecting ... candidate=1/2` (или обратно к следующему кандидату по циклу).
   - AirPad: соответствующий `event=connect-failed`/`event=engine-close` и затем `event=connecting` на другом candidate.
5. Включить `ws://` обратно и проверить возврат к живому кандидату.

Acceptance criteria:
- После старта/переподключения оба клиента показывают одинаковые `event/candidate/phase/scheme` поля в статусах.
- Последовательность `wss -> ws` наблюдается и фиксируется в логах обоих клиентов.
- После падения активного кандидата есть попытка ротации на следующий кандидат с явной записью в логах.

## Mini observer / чеклист-команда

Запуск:

```bash
./scripts/ws-diagnostic-checklist.sh --android-log /tmp/android.log --airpad-log /tmp/airpad.log --mode all --strict
```

Что делает:

- `--mode all` прогоняет три шага: `offline`, `mixed`, `rollback`.
- `--strict` делает провал по `exit 1`, если хотя бы один шаг не проходит.
- Без `--strict` скрипт выводит статус каждого шага как `[x]/[ ]` для ручной сверки.

Мини-поток запуска (чеклист):

1. Собрать логи Android и AirPad в файлы.
2. Убедиться, что в логах есть строки `[ws-state] ...`.
3. Запустить скрипт и проверить блоки `MODE:... RESULT`.
