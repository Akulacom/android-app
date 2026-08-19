# SEEDANCE 2.0 — СТРОГОЕ РЕДАКТИРОВАНИЕ С ПОЛНЫМ ЛОКОМ ДВИЖЕНИЯ
### Замена фона/одежды/света/объектов на реальном футаже БЕЗ изменения движения человека, камеры, губ и тайминга

---

## 1. ДИАГНОЗ: ПОЧЕМУ ЛОМАЛОСЬ ДВИЖЕНИЕ

Твоя проблема — не в модели, а в том, КАК видео попадает в генерацию. У Seedance есть два принципиально разных отношения к загруженному видео:

| Режим | Что делает модель | Результат |
|---|---|---|
| **Видео как РЕФЕРЕНС** (reference / universal generation) | Извлекает «идею» движения и камеры и пере-генерит их по мотивам | Движение «похожее», но НЕ то же. Камера уезжает, жесты меняются, появляются новые слова |
| **Видео как ИСТОЧНИК ДЛЯ ПРАВКИ** (Video Edit / V2V edit) | Держит субъекта, композицию и движение, переписывает только названное | То, что тебе нужно |

**Ошибка №1 — режим.** Если платформа воспринимает клип как референс, никакой промпт не спасёт: модель по определению «вдохновляется», а не сохраняет. Проверяй, что выбран именно режим **редактирования видео** (Video Edit / «Редактирование»), а не генерация с видео-референсом.

**Ошибка №2 — описание действия в промпте.** Это главная ловушка моих прошлых шаблонов. Когда пишешь «he talks and gestures to camera» — ты не описываешь, ты **даёшь команду сгенерировать** говорящего и жестикулирующего человека. Модель честно пере-анимирует: новые слова, новые жесты. **Действие человека в промпте описывать НЕЛЬЗЯ. Его можно только запирать.**

**Ошибка №3 — лишние слова.** «Cinematic look», «filmic grade», описание сцены, настроения, стиля — каждое лишнее слово это разрешение что-то пересобрать. Для строгой правки промпт должен быть почти пустым: одно изменение + лок-блок.

**Ошибка №4 — длительность.** Если длительность генерации не совпадает с исходником, модель обязана пере-таймить движение. Всегда: **длительность вывода = длительность исходника.**

---

## 2. ГЛАВНАЯ ФОРМУЛА: ОДНО ИЗМЕНЕНИЕ + FULL LOCK

Промпт строгой правки состоит из двух частей и НИЧЕГО больше:

```
[ЧТО МЕНЯЕМ — одно предложение]
+
[FULL LOCK BLOCK — копипаст ниже]
```

### 🔒 FULL LOCK BLOCK (копируй как есть, это твой главный инструмент)

```
This is a [background replacement] edit only. Treat @video1 as locked source
footage, not a creative reference.
Preserve one-to-one, frame by frame, with no reinterpretation:
- the person's identity, face, facial expressions and exact lip movement;
- all body motion: arms, hands, fingers, legs, head — every gesture exactly
  as in the source, same speed, same timing;
- the camera: movement, trajectory, framing, angle, composition, lens
  perspective and shake — copy the source camera exactly;
- shot duration, pacing and all timing. Do not re-time anything.
Do not add, remove or change any dialogue, speech, words, voice or mouth
movement. Do not add new objects, people, gestures, camera moves or scene
cuts. Do not stylize.
Change ONLY: [одно изменение].
Output duration equals source duration.
```

В скобках `[background replacement]` подставляй тип правки: background replacement / clothing replacement / relighting / object replacement. Это сразу сообщает модели класс задачи.

### Почему это работает
- «locked source footage, not a creative reference» — прямо запрещает режим «вдохновения»
- Лок расписан **пофрагментно** (пальцы, губы, тряска камеры) — модель уважает конкретику, «keep everything» для неё слишком расплывчато
- Отдельный запрет на речь и рот — новые слова появляются, потому что модель генерит нативное аудио и подгоняет губы под него; запрет `no dialogue, no mouth movement changes` закрывает канал
- «Do not stylize» — убивает самодеятельность с грейдом и «улучшениями»

---

## 3. ШАБЛОНЫ ПОД ТВОИ ЗАДАЧИ (строгая версия)

### 3.1 Замена фона (твой главный кейс)
```
Replace the background with [late evening city street, blue-gradient sky].
Match the lighting on the person to the new background: [направление и цвет,
например: cool ambient key, warm rim from street lamps]. Keep the person's
edges clean, no halos.

This is a background replacement edit only. Treat @video1 as locked source
footage, not a creative reference. Preserve one-to-one, frame by frame:
the person's identity, face, expressions and exact lip movement; all body
motion — arms, hands, legs, head — every gesture exactly as in the source,
same speed, same timing; the camera movement, trajectory, framing, angle,
composition and shake — copy the source camera exactly; shot duration and
pacing. Do not re-time anything. Do not add, remove or change any dialogue,
speech or mouth movement. Do not add new objects, people, gestures, camera
moves or scene cuts. Do not stylize. Change ONLY the background.
Output duration equals source duration.
```
⚠️ Единственное «творческое», что здесь разрешено — релайт человека под новый фон. Без него получится наклейка. Всё остальное заперто.

### 3.2 Замена одежды
```
Replace only his [black t-shirt] with [a dark-green wool bomber jacket,
matte fabric]. The new clothing follows the source body motion exactly,
with realistic folds.

This is a clothing replacement edit only. Treat @video1 as locked source
footage. Preserve one-to-one: face, lip movement, all body motion and
gestures at source speed and timing; the camera exactly as in the source;
the background, lighting and duration. No dialogue changes, no new elements,
no scene cuts, no stylization. Change ONLY the specified clothing item.
Output duration equals source duration.
```

### 3.3 День → ночь / погода / свет
```
Change the time of day to night: street lamps, cool blue ambient light,
relight the person accordingly with consistent shadow direction.

This is a relighting edit only. Treat @video1 as locked source footage.
Preserve one-to-one: the person's motion, gestures, lip movement and timing;
the camera exactly as in the source; the location, composition and duration.
No dialogue changes, no new elements, no scene cuts. Change ONLY lighting
and time of day. Output duration equals source duration.
```

### 3.4 Замена объекта в руках / в кадре
```
Replace the [phone in his hand] with [the object from @image1], same size,
same position, same grip. Match contact shadows and reflections.

This is an object replacement edit only. Treat @video1 as locked source
footage. Preserve one-to-one: the person's face, lip movement, hand and
body motion at source timing; the camera exactly as in the source; the
background, lighting and duration. Do not change the grip or pose. No
dialogue changes, no new elements, no cuts. Change ONLY the specified object.
Output duration equals source duration.
```
(Объект замены — картинкой через @image1, не словами.)

### 3.5 Добавление эффекта (огонь, голограмма, энергия) без пере-анимации
```
At [2s], add [realistic flames on his jacket sleeve / a translucent blue
hologram floating above the device]. The effect's light realistically spills
onto the person and nearby surfaces.

This is an effect-addition edit only. Treat @video1 as locked source footage.
The effect is ADDED ON TOP of the source: the person's motion, gestures, lip
movement, the camera and all timing remain exactly as in the source, frame by
frame. No dialogue changes, no new gestures, no camera changes, no cuts.
Change ONLY: add the specified effect. Output duration equals source duration.
```
Ключевая фраза — **«added on top of the source»**: эффект накладывается, а не «сцена с эффектом генерится заново».

---

## 4. АНТИ-ПРАВИЛА: ЧЕГО НЕ ПИСАТЬ В СТРОГОЙ ПРАВКЕ

❌ **Не описывай, что делает человек** («talks to camera», «gestures», «walks») — это команда пере-генерить действие. Действие только запираем: «preserve all body motion exactly as in the source».

❌ **Не описывай движение камеры словами** («handheld», «push in», «tracking») — это команда на НОВОЕ движение камеры. Камеру только запираем: «copy the source camera exactly». Слова типа handheld нужны только при генерации с нуля.

❌ **Не добавляй стиль и настроение** («cinematic», «moody», «premium look») — триггерит пересборку картинки.

❌ **Не проси несколько изменений** — каждое дополнительное изменение расширяет зону, которую модель считает «разрешённой к пересборке».

❌ **Не пиши SFX/музыку, если в исходнике живая речь** — запрос звука провоцирует новое аудио, а новое аудио тянет за собой новые губы. Для клипов с речью: `Keep the original audio. Do not generate new audio.` (если платформа позволяет) или монтируй свой звук в посте.

---

## 5. ЛЕСТНИЦА ЭСКАЛАЦИИ: ЕСЛИ ДВИЖЕНИЕ ВСЁ РАВНО ПЛЫВЁТ

Иди по шагам, не прыгай:

1. **Обрежь промпт до минимума.** Одно предложение изменения + лок-блок. Убрал всё описательное? Часто этого достаточно.
2. **Укороти клип до 4–6 секунд.** Чем длиннее клип, тем больше свободы модель себе даёт. Длинную сцену режь на куски, правь по кускам, склеивай в посте — тайминг совпадёт, потому что каждый кусок заперт на свою длительность.
3. **4K.** Лица, губы и мелкая моторика держатся в 4K и разваливаются в 1080p.
4. **Проверь режим ещё раз.** На части платформ (Dreamina/Jimeng и агрегаторы) есть отдельный пайплайн «Video Edit» и отдельный «Reference». Нужен Edit.
5. **Region inpainting, если платформа умеет** (в Dreamina есть выделение области): выделяешь ТОЛЬКО фон — человек защищён механически, модель физически не может тронуть его пиксели. Для замены фона это самый надёжный генеративный путь.
6. **Понизь силу правки / creativity**, если платформа даёт ползунок (edit strength). Залочь seed для повторяемости.
7. **Финальный fallback — гибрид с постом.** Честно: генеративный V2V пере-рендерит КАЖДЫЙ пиксель кадра заново, попиксельную гарантию не даёт ни одна модель. Если шот критичный и нужно потом смерживать дорожки:
   - Генери в Seedance только НОВЫЙ ФОН (пустой, без человека, с камерой из твоего клипа: `@video1 for the camera movement only, generate the same shot of [окружение] with no people`) 
   - Человека вырезай из оригинала ротоскопом/кеингом в AE (Roto Brush) и сажай на сгенерённый фон
   - Оригинальные пиксели человека = идеальные движения, губы и синк по определению

---

## 6. ПРАВИЛЬНАЯ ПОДГОТОВКА ИСХОДНИКА

- Обрежь до нужного куска ДО загрузки (2–15 сек лимит edit-режима; сладкая зона 4–8 сек)
- Один непрерывный кадр без склеек — склейки в исходнике сбивают модель
- Чистое начало: убери подготовительные движения в начале клипа
- Стабильный свет в исходнике = стабильный релайт на выходе
- Для смерживания нескольких правок одного исходника: **все правки гони от ОРИГИНАЛА**, а не от предыдущей правки — каждая генерация добавляет дрейф

---

## 7. ЧЕК-ЛИСТ ПЕРЕД ОТПРАВКОЙ

- [ ] Режим = Video EDIT, не reference?
- [ ] В промпте НЕТ описания действий человека?
- [ ] В промпте НЕТ описания движения камеры (только «copy the source camera exactly»)?
- [ ] НЕТ слов про стиль/настроение/cinematic?
- [ ] Изменение ОДНО?
- [ ] Лок-блок вставлен целиком (губы, пальцы, тряска камеры, тайминг)?
- [ ] Запрет на диалоги/речь/рот прописан?
- [ ] Output duration = source duration?
- [ ] Клип 4–8 сек, один непрерывный кадр?
- [ ] 4K, если в кадре лицо?
- [ ] Правка идёт от оригинала, а не от прошлой правки?

---

## Источники
- Официальный гайд Seedance 2.0 (Volcengine): паттерн «Keep the hands, timing, and camera movement unchanged» / «Preserve the original hand motion and camera move»
- WaveSpeed Seedance 2.0 Video-Edit: reference video = subject identity + composition + motion, модель переписывает только названное; лимит 2–15 сек
- seedanceai.cc: region inpainting — выделение области с сохранением остального кадра
- Опыт практиков (WaveSpeedAI/Dora, PromeAI, HeyMarmot): референс = «инструкция пикселями», чистый исходник 3–8 сек, головы/хвосты резать, одна идея на клип, правки хирургическим языком
- PromeAI: правки от оригинала, edge healing, лог Model/Seed/Prompt
