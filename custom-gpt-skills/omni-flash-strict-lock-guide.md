# GEMINI OMNI FLASH — СТРОГОЕ РЕДАКТИРОВАНИЕ С ПОЛНЫМ ЛОКОМ ДВИЖЕНИЯ
### Замена фона/одежды/света на своём футаже БЕЗ изменения движения человека, камеры, губ и тайминга

---

## 1. ДИАГНОЗ: ПОЧЕМУ OMNI ЛОМАЛ ТВОИ КАДРЫ

У Omni Flash три встроенных поведения, которые убивают строгую правку, если их не выключить явно:

1. **Omni по умолчанию строит «историю».** Официальная документация: без явного запрета модель сама режет видео на несколько шотов и «придумывает интересный нарратив». Отсюда — новые ракурсы и смены камеры, которые ты не просил. Лечится обязательным `No scene cuts. Single continuous shot as in the source.`

2. **Omni всегда генерит нативное аудио.** Если не запретить — модель сочиняет речь/звуки и **подгоняет губы под новое аудио**. Отсюда — новые слова изо рта. Лечится `No dialogue. No speech. Do not change the mouth movement.`

3. **Любое описание = разрешение пересобрать.** Официальное правило Google: длинные описательные промпты при редактировании приводят к незапланированным изменениям. Если ты в промпте описал, что человек делает — модель это пере-анимирует по-своему. **Действие не описываем. Только запираем.**

Плюс проверь способ загрузки: видео должно попадать в модель как **объект редактирования** (в приложении — «редактировать это видео», в API — файл + `task: "edit"`), а не как референс-вдохновение.

---

## 2. ФОРМУЛА СТРОГОЙ ПРАВКИ В OMNI

Omni любит короткие промпты — но «короткий» не значит «без защиты». Формула:

```
[Одно изменение — одна фраза] + [LOCK-хвост] + Keep everything else the same.
```

### 🔒 LOCK-хвост для Omni (копируй как есть)

```
Do not change the camera movement, framing, angle or composition — keep the
exact camera work of the original video. Do not change the person's movements:
keep every gesture, arm, hand, leg and head motion, facial expression and lip
movement exactly as in the original, with the same timing and duration.
No dialogue, no speech, no new sounds from the person. No scene cuts —
single continuous shot as in the source. Do not re-time or trim anything.
Keep everything else the same.
```

Отличие от Seedance: здесь НЕ нужен блок с описанием исходника (@source) — Omni видит клип в контексте диалога. Сам промпт короче, но лок-хвост обязателен полностью: официального «Keep everything else the same» одного НЕ хватает для камеры, губ и тайминга — их нужно называть отдельно.

---

## 3. ШАБЛОНЫ ПОД ТВОИ ЗАДАЧИ

### 3.1 Замена фона (главный кейс)
```
Replace the background with [late evening city street, blue-gradient sky].
Relight the person naturally to match the new background.
Do not change the camera movement, framing, angle or composition — keep the
exact camera work of the original video. Do not change the person's movements:
keep every gesture, arm, hand, leg and head motion, facial expression and lip
movement exactly as in the original, with the same timing and duration.
No dialogue, no speech. No scene cuts — single continuous shot as in the
source. Do not re-time anything. Keep everything else the same.
```

### 3.2 Замена одежды
```
Replace his black t-shirt with a dark-green wool bomber jacket.
Do not change anything else: keep the person's face, lip movement, all body
motion and gestures, the camera work, the background, the lighting, the
timing and the duration exactly as in the original. No dialogue, no speech,
no scene cuts. Keep everything else the same.
```

### 3.3 День → ночь / свет / погода
```
Change the lighting to night: street lamps, cool blue ambient, relight the
person consistently.
Do not change the location, the person's movements, lip movement, the camera
work, the timing or the duration. No dialogue, no scene cuts.
Keep everything else the same.
```

### 3.4 Замена объекта
```
Replace the phone in his hand with [объект], same size and position, same grip.
Do not change the grip, the hand motion, the person's movements, lip movement,
the camera work, the background, the timing or the duration. Do not add any
new objects or hands. No dialogue, no scene cuts. Keep everything else the same.
```

### 3.5 Эффект поверх кадра (огонь, голограмма)
```
Add realistic flames on his jacket sleeve starting at 2s. The fire's light
naturally illuminates him. The effect is added on top of the original video:
the person's movements, lip movement, the camera work, the timing and the
duration remain exactly as in the original. No dialogue, no new gestures,
no scene cuts. Keep everything else the same.
```

---

## 4. АНТИ-ПРАВИЛА ДЛЯ OMNI

❌ **Не описывай действие человека и движение камеры** — только запирай («keep the exact camera work of the original»). Слова «handheld», «push in» и т.п. — команды на НОВУЮ камеру, они нужны только при генерации с нуля.

❌ **Не раздувай промпт.** Официально: подробные промпты при редактировании = лишние изменения. Всё описательное сверх изменения и лок-хвоста — выкинуть.

❌ **Не наслаивай правки на правки для важных кадров.** Консистентность плывёт после 3–4 ходов. Каждую строгую правку запускай **от оригинального видео** новым заходом, а не поверх предыдущего результата — иначе дрейф движений накапливается и дорожки не смержатся.

❌ **Не проси стиль, если не просили** («cinematic», «dramatic») — пересборка картинки.

❌ **Не смешивай изменения.** Фон отдельно, одежда отдельно. Каждое — свой заход от оригинала.

---

## 5. ЕСЛИ ВСЁ РАВНО ПЛЫВЁТ — ЭСКАЛАЦИЯ

1. **Сократи промпт** до одной фразы изменения + лок-хвост. Убери всё.
2. **Укороти клип.** Omni держит максимум 10 секунд; на 4–6-секундных кусках лок заметно стабильнее. Режь сцену на куски, правь каждый от оригинала, склеивай в посте.
3. **Проверь, что видео загружено как объект редактирования** (не как референс). В API — `task: "edit"`.
4. **Один и тот же кусок несколько раз** — генерация недетерминирована; иногда 2-й/3-й прогон того же промпта попадает в лок точно. Отбирай дубль.
5. **Смена инструмента под задачу.** Если Omni стабильно пере-анимирует конкретный тип шота (частая жалоба — сложная моторика рук и речь) — этот шот гони через Seedance 2.0 Video-Edit со строгим лок-блоком (см. второй файл): у Seedance режим edit жёстче привязан к исходному движению.
6. **Финальный fallback — гибрид с постом** (для критичных шотов, которые надо смерживать):
   - Omni/Seedance генерит только новый фон без человека
   - Человек вырезается из оригинала в AE (Roto Brush / кеинг) и сажается на новый фон
   - Оригинальные пиксели человека = движения, губы и тайминг совпадают на 100% по определению. Генеративный V2V пере-рендерит весь кадр и попиксельной гарантии не даёт ни в одной модели — для монтажа дорожек это единственный железный путь.

---

## 6. ПАМЯТКА ПО ЛИМИТАМ OMNI (для строгих правок)

- Клип ≤ 10 сек, 720p нативно → мелкая моторика и губы на пределе; критичные лица лучше в Seedance 4K
- Липсинк в генерации живёт 6–7 секунд — ещё одна причина резать куски короче
- Негативных промптов как параметра нет — все запреты («Do not…», «No…») пишутся в сам промпт
- Редактирование ЗАГРУЖЕННЫХ видео недоступно в ЕЭЗ/Швейцарии/UK (в Таиланде работает)
- Аудио-референсы в API не поддерживаются; свой звук — в посте
- Каждая правка = новая генерация и расход квоты; строгие правки жги от оригинала, отбирай лучший дубль

---

## 7. ЧЕК-ЛИСТ ПЕРЕД ОТПРАВКОЙ

- [ ] Видео загружено как объект редактирования (не референс)?
- [ ] Изменение ОДНО и описано одной фразой?
- [ ] НЕТ описания действий человека и движения камеры?
- [ ] LOCK-хвост вставлен целиком: камера, жесты, губы, тайминг, длительность?
- [ ] Есть «No dialogue, no speech»?
- [ ] Есть «No scene cuts — single continuous shot as in the source»?
- [ ] Есть «Keep everything else the same» в конце?
- [ ] Клип ≤ 6–8 сек?
- [ ] Правка идёт от ОРИГИНАЛА, а не от прошлой правки?
- [ ] Для критичного лица/моторики — рассмотрен Seedance 4K или гибрид с AE?

---

## Источники
- Официальная документация Google (ai.google.dev/gemini-api/docs/omni): «simple prompts work best for editing», дефолтное поведение мульти-шот/нарратив, «Keep everything else the same», task="edit", негативы в промпте, лимиты регионов и аудио
- Тесты invideo (30+ генераций): липсинк 6–7 сек, слабая мульти-персонная речь
- Продакшн-фидбэк WaveSpeed: дрейф после 3–4 правок, «важный кадр — с первого промпта», критичный текст/детали — в пост
