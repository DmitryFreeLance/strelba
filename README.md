# Training Diary Telegram Bot (Java + SQLite)

Телеграм-бот для дневника тренировок.

## Что умеет

- Последовательный опрос пользователя по пунктам `A1..M1`.
- Сохранение ответов в SQLite.
- Уведомление администраторам: `Новая запись в дневнике!` + все данные записи.
- Inline-кнопка `Заполнить еще` после завершения опроса.
- Админ-панель (inline кнопки):
  - `Все результаты` (пагинация по датам)
  - `Все пользователи` (список с ID)
  - `Добавить админа`
  - `Удалить админа`
  - `Выйти из панели`

## Переменные окружения

- `BOT_TOKEN` - токен бота (обязательно)
- `BOT_USERNAME` - username бота без `@` (необязательно, по умолчанию `StrelbaDiaryBot`)
- `SQLITE_PATH` - путь к файлу БД (необязательно, по умолчанию `data/bot.db`)
- `INITIAL_ADMINS` - список Telegram ID админов через запятую (необязательно)

## Локальный запуск

```bash
mvn -DskipTests package
BOT_TOKEN=123456:ABC BOT_USERNAME=YourBot INITIAL_ADMINS=111111111 java -jar target/training-diary-bot-1.0.0.jar
```

## Docker

Сборка образа:

```bash
docker build -t training-diary-bot .
```

Запуск контейнера:

```bash
docker run -d --name training-diary-bot \
  -e BOT_TOKEN=123456:ABC \
  -e BOT_USERNAME=YourBot \
  -e INITIAL_ADMINS=111111111,222222222 \
  -e SQLITE_PATH=/opt/bot/data/bot.db \
  -v $(pwd)/data:/opt/bot/data \
  training-diary-bot
```

Логи:

```bash
docker logs -f training-diary-bot
```
