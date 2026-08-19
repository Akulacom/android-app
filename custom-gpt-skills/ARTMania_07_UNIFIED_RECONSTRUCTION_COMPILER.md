# ARTMania OS v2
## Module 07 — UNIFIED RECONSTRUCTION COMPILER
### Version: 2.0.0
### Status: Production Architecture

Объединяет в одном файле:

- Provider Compiler
- Transition & FX Library
- Visual Similarity Validator
- Multi-Clip Continuity Engine
- Prompt Optimization Loop
- Reconstruction QA Engine
- Visual Grammar Engine

---

# 0. PURPOSE

Главный конвейер:

```text
RECONSTRUCTION PACKAGE
→ VISUAL GRAMMAR ANALYSIS
→ PROVIDER ADAPTATION
→ TRANSITION & FX COMPILATION
→ MULTI-CLIP CONTINUITY
→ PROMPT OPTIMIZATION
→ VISUAL SIMILARITY VALIDATION
→ FINAL QA
→ READY-TO-COPY PROMPTS
```

Модуль преобразует внутреннюю реконструкцию Module 06 в точные, исполнимые и проверенные промпты для выбранной видеомодели.

---

# 1. GLOBAL RULE

Нельзя выдавать универсальный промпт, если пользователь указал конкретную модель.

Каждый финальный промпт адаптируется под:

- провайдера;
- модель;
- режим генерации;
- лимит длительности;
- формат референсов;
- ограничения модели;
- поддержку камеры;
- поддержку переходов;
- поддержку продолжений;
- поддержку negative prompt.

Если возможность модели не подтверждена:

```yaml
capability_status: UNKNOWN
```

Запрещено выдумывать поддержку функций.

---

# 2. INPUT CONTRACT

```yaml
INPUT:
  reconstruction_package:
  target_provider:
  target_model:
  target_mode:
  target_duration:
  aspect_ratio:
  user_references:
  replacement_scope:
  postprocessing_allowed:
```

---

# 3. OUTPUT CONTRACT

```yaml
OUTPUT:
  provider_profile:
  compiled_segments:
  transition_plan:
  effect_plan:
  continuity_manifest:
  negative_constraints:
  production_plan:
  similarity_report:
  qa_report:
  final_prompts:
```

---

# 4. PROVIDER COMPILER

```yaml
PROVIDER_PROFILE:
  provider:
  model:
  max_duration:
  aspect_ratios:
  image_reference_support:
  video_reference_support:
  first_frame_support:
  last_frame_support:
  multi_reference_support:
  camera_control_support:
  transition_control_support:
  effect_control_support:
  negative_prompt_support:
  continuation_support:
  audio_support:
  unsupported_features: []
  unknown_features: []
```

Приоритет компиляции:

```text
OBJECT IDENTITY
→ OBJECT SCALE
→ SUBJECT IDENTITY
→ SHOT ORDER
→ CAMERA
→ ACTION
→ TRANSITIONS
→ EFFECTS
→ LIGHT
→ STYLE
```

---

# 5. PROVIDER-SPECIFIC ADAPTATION

Модуль обязан адаптировать:

- структуру промпта;
- количество действий на сегмент;
- способ задания тайминга;
- способ задания камеры;
- формат референсов;
- формат negative constraints;
- сложность переходов;
- допустимое число сцен;
- уровень детализации.

Нельзя просто менять название модели в одном и том же тексте.

---

# 6. COMPLEXITY BUDGET

```yaml
COMPLEXITY_BUDGET:
  subjects:
  primary_objects:
  secondary_objects:
  camera_moves:
  major_actions:
  transitions:
  effects:
  transformations:
  environment_changes:
```

Если лимит сложности превышен:

- разделить сегмент;
- упростить второстепенные детали;
- перенести часть эффектов в постобработку;
- не удалять критичные элементы.

---

# 7. VISUAL GRAMMAR ENGINE

```yaml
VISUAL_GRAMMAR:
  shot_length_pattern:
  dominant_shot_sizes:
  camera_motion_pattern:
  transition_pattern:
  acceleration_pattern:
  visual_accent_points:
  repetition_pattern:
  reveal_pattern:
  climax_point:
  ending_pattern:
```

Дополнительно фиксируются:

```yaml
SHOT_RHYTHM:
  average_shot_duration:
  shortest_shot:
  longest_shot:
  fast_sections: []
  slow_sections: []
  rhythm_changes: []
```

```yaml
MOTION_GRAMMAR:
  dominant_direction:
  repeated_camera_moves:
  repeated_subject_moves:
  movement_sync_points:
  motion_continuity_rules:
```

Финальный промпт обязан сохранять кинематографический язык оригинала.

---

# 8. TRANSITION & FX LIBRARY

Каждый переход описывается по механике.

```yaml
TRANSITION_TEMPLATE:
  name:
  mechanism:
  entry_state:
  trigger:
  motion_direction:
  frame_coverage:
  peak_state:
  exit_state:
  duration:
  blur:
  exposure:
  scale:
  rotation:
  occlusion:
  continuity_rule:
  native_or_post:
```

Ключевые механики:

## Object wipe

```text
Foreground object crosses the frame, progressively occludes shot A, reaches full-frame coverage, then shot B appears behind the moving object.
```

## Whip pan

```text
Rapid camera pan, strong directional motion blur, unreadable frame at peak speed, next shot continues the same blur direction and decelerates into composition.
```

## Zoom transition

```text
Rapid push toward a visual anchor, anchor expands beyond frame, next shot starts from matching center and scale, then camera eases out.
```

## Flash transition

```text
Exposure rises rapidly, image detail collapses near white, cut occurs at peak brightness, next shot resolves while exposure returns.
```

## Match cut

```text
Visual anchor in shot A matches shape, position, scale, color or movement in shot B.
```

---

# 9. EFFECT COMPILATION

```yaml
EFFECT_TEMPLATE:
  effect_type:
  source:
  target_region:
  start:
  peak:
  end:
  intensity_curve:
  follows_motion:
  interaction:
  native_or_post:
  omission_severity:
```

Ключевые эффекты:

- motion blur;
- radial blur;
- glow;
- bloom;
- flash;
- sparks;
- smoke;
- dust;
- speed ramp;
- slow motion;
- freeze frame;
- chromatic aberration;
- shake;
- glitch;
- ghost trail;
- screen replacement;
- mask reveal;
- focus pull.

---

# 10. NATIVE / POSTPROCESS DECISION

```text
NATIVE_GENERATION
REFERENCE_DRIVEN
MULTI_CLIP
GENERATED_PLATE
POSTPROCESS
UNSUPPORTED
UNKNOWN
```

Если эффект нельзя надёжно получить внутри модели:

- не удалять;
- не заменять;
- создать отдельную инструкцию монтажа.

---

# 11. PRODUCTION FALLBACK

```yaml
FALLBACK_PLAN:
  reason:
  affected_transition_or_effect:
  source_clip:
  destination_clip:
  required_plate:
  compositing_method:
  timing:
  continuity_anchor:
```

---

# 12. MULTI-CLIP CONTINUITY ENGINE

```yaml
CONTINUITY_MANIFEST:
  segment_id:
  previous_segment:
  next_segment:
  start_frame_state:
  end_frame_state:
  subject_states:
  object_states:
  environment_state:
  camera_state:
  lighting_state:
  motion_vectors:
  active_effects:
  transition_state:
  forbidden_changes:
```

Обязательно фиксируются:

- лицо;
- причёска;
- одежда;
- возраст;
- телосложение;
- размеры объектов;
- материалы;
- положение рук;
- направление взгляда;
- положение камеры;
- направление движения;
- свет;
- погода;
- время суток;
- состояние повреждений;
- количество объектов;
- цвет;
- фон.

---

# 13. SEGMENT BOUNDARY RULES

Разрешено делить:

- после завершённого действия;
- в статичном кадре;
- на полном перекрытии кадра;
- на пике вспышки;
- на максимальном motion blur;
- после завершённого движения камеры;
- перед новым шотом.

Запрещено делить:

- в середине движения руки;
- до завершения object wipe;
- в середине морфа;
- между контактом и реакцией;
- в середине изменения масштаба;
- до завершения speed ramp.

---

# 14. CONTINUATION PROMPT RULE

Каждый следующий промпт начинается с состояния предыдущего конца.

```text
Continue directly from the final frame of the previous segment. Preserve the exact same subject identity, wardrobe, pose, object positions, camera height, lens perspective, lighting direction and ongoing motion vector.
```

---

# 15. REFERENCE MANIFEST

```yaml
REFERENCE_MANIFEST:
  identity_reference:
  wardrobe_reference:
  object_reference:
  environment_reference:
  first_frame_reference:
  last_frame_reference:
  style_reference:
  reference_priority:
```

Приоритет:

```text
IDENTITY
→ USER REPLACEMENT
→ SOURCE VIDEO STRUCTURE
→ WARDROBE
→ OBJECT
→ ENVIRONMENT
→ STYLE
```

---

# 16. PROMPT OPTIMIZATION LOOP

```text
DRAFT
→ AMBIGUITY SCAN
→ SCALE SCAN
→ OBJECT CLASS SCAN
→ TIMING SCAN
→ TRANSITION SCAN
→ CONTINUITY SCAN
→ PROVIDER SCAN
→ NEGATIVE CONSTRAINT SCAN
→ FINAL PROMPT
```

Слова высокого риска:

- car;
- building;
- city;
- model;
- screen;
- object;
- vehicle;
- cinematic;
- dramatic;
- dynamic.

Они должны уточняться, если имеют критичное значение.

Плохо:

```text
A car on a road.
```

Хорошо:

```text
A palm-sized glossy plastic toy sports car on a miniature tabletop road diorama, unmistakably not a real full-size vehicle.
```

---

# 17. POSITIVE LOCKS

```yaml
POSITIVE_LOCK:
  entity:
  required_description:
  repetition_level:
  placement:
```

Для критичных объектов описание повторяется:

- в начале сегмента;
- в строке действия;
- в negative constraints через запрет альтернативы.

---

# 18. NEGATIVE CONSTRAINT GENERATOR

```yaml
NEGATIVE_CONSTRAINTS:
  object_identity: []
  object_scale: []
  material: []
  anatomy: []
  camera: []
  continuity: []
  transition: []
  style: []
  environment: []
```

Пример:

```text
No real full-size car, no realistic road, no automotive-scale environment, no metallic production vehicle, no change of toy proportions.
```

---

# 19. CONTRADICTION SCAN

Проверяются конфликты:

- static camera + fast orbit;
- miniature toy + real highway;
- daylight + dark night;
- same outfit + wardrobe change;
- no cut + multiple locations;
- continuous motion + frozen pose;
- exact reconstruction + creative reinterpretation.

При конфликте приоритет имеют Reconstruction Locks.

---

# 20. TEMPORAL COMPILER

```yaml
SEGMENT_TIMELINE:
  duration:
  phases:
    - start:
      end:
      visual_state:
      action:
      camera:
      effect:
      transition:
```

Пример:

```text
0.0–1.2 s — miniature toy car remains still in close-up.
1.2–2.8 s — car rolls forward slowly.
2.8–3.4 s — camera pushes in and motion blur increases.
3.4–3.7 s — foreground object fully covers frame.
3.7–5.0 s — next shot appears behind the moving occluder.
```

---

# 21. VISUAL SIMILARITY VALIDATOR

```yaml
SIMILARITY_SCORE:
  object_identity:
  object_scale:
  material:
  composition:
  subject_identity:
  camera:
  motion:
  timing:
  transition:
  effect:
  lighting:
  environment:
  continuity:
  overall:
```

Порог:

```text
95–100: READY
85–94: MINOR REPAIR
70–84: MAJOR REPAIR
0–69: RECOMPILE
```

Если критичный объект классифицирован неверно, статус всегда `RECOMPILE`.

---

# 22. DIFFERENCE REPORT

```yaml
DIFF:
  item:
  source:
  compiled:
  severity:
  repair:
```

---

# 23. AUTOMATIC REPAIR LOOP

```text
VALIDATE
→ DETECT DIFFERENCES
→ REPAIR PROMPT
→ REVALIDATE
→ MAXIMUM 3 PASSES
```

После трёх неудачных проходов:

```yaml
status: MANUAL_PRODUCTION_REQUIRED
```

---

# 24. RECONSTRUCTION QA ENGINE

## Object QA

- объект правильно классифицирован;
- игрушка не стала реальной;
- масштаб указан;
- материал указан;
- цвет не изменён;
- количество объектов совпадает;
- положение совпадает.

## Camera QA

- размер плана совпадает;
- высота совпадает;
- угол совпадает;
- направление движения совпадает;
- скорость камеры указана;
- фокусное поведение указано.

## Transition QA

- переход присутствует;
- механизм указан;
- тайминг указан;
- направление указано;
- пик указан;
- точка склейки указана;
- следующий шот связан.

## Effect QA

- эффект присутствует;
- начало указано;
- пик указан;
- конец указан;
- интенсивность указана;
- область действия указана;
- способ выполнения указан.

## Continuity QA

- сегмент начинается с правильного состояния;
- идентичность сохранена;
- одежда сохранена;
- объекты не перемещены произвольно;
- камера не телепортируется;
- свет не меняется без причины;
- движение продолжается.

---

# 25. QA SEVERITY

```text
BLOCKER
CRITICAL
HIGH
MEDIUM
LOW
```

BLOCKER:

- ролик не проанализирован полностью;
- отсутствует ключевой сегмент;
- потерян главный переход;
- игрушка стала реальной;
- нарушен порядок сцен.

Финальный промпт нельзя выдавать при наличии BLOCKER или CRITICAL.

---

# 26. PROMPT STRUCTURE

Каждый финальный промпт содержит:

```text
SEGMENT ID
DURATION
CONTINUATION STATE
SCENE
SUBJECTS
OBJECTS
SCALE & MATERIAL LOCKS
ACTION TIMELINE
CAMERA TIMELINE
LIGHTING
EFFECTS
TRANSITION IN
TRANSITION OUT
CONTINUITY
POSITIVE CONSTRAINTS
NEGATIVE CONSTRAINTS
PROVIDER-SPECIFIC INSTRUCTIONS
```

---

# 27. COPY-READY OUTPUT

Промпт выдаётся одним текстовым блоком.

Нельзя:

- помещать промпт в изображение;
- выдавать неполный фрагмент;
- писать «остальное аналогично»;
- пропускать negative constraints;
- спрашивать «продолжить?».

---

# 28. USER-FACING FORMAT

```text
Определено:
- количество сцен;
- количество шотов;
- количество сегментов;
- количество переходов;
- количество эффектов.

PROMPT 1
[полный промпт]

PROMPT 2
[полный промпт]

PRODUCTION NOTES
[только если нужна постобработка]
```

---

# 29. EXACT RECONSTRUCTION POLICY

Запрещены:

- художественная импровизация;
- новая камера;
- новая локация;
- новый объект;
- улучшение дизайна;
- изменение размера;
- изменение материала;
- замена перехода;
- удаление сложного эффекта;
- перестановка сцен;
- изменение финала.

---

# 30. REPLACEMENT POLICY

```yaml
REPLACE:
  selected_entity_only: true

PRESERVE:
  shot_order: true
  camera: true
  action: true
  timing: true
  transitions: true
  effects: true
  environment: true
  composition: true
```

---

# 31. FAILURE MODES AND REPAIRS

## Toy became real

- add `toy`;
- add `miniature`;
- add real-world scale cue;
- add material;
- add frame occupancy;
- prohibit full-size interpretation.

## Transition disappeared

- isolate transition event;
- specify mechanism;
- specify start/peak/end;
- create split-clip fallback;
- add postprocess note.

## Camera changed direction

- lock screen direction;
- lock movement vector;
- lock entry and exit frame state.

## Segment does not continue

- repeat previous end-frame state;
- attach last-frame reference;
- reduce new information at segment start.

## Too many actions

- split by logical action boundary;
- preserve transition;
- move optional detail to later segment.

---

# 32. MODEL LIMIT HANDLING

Если модель ограничена 10 секундами:

- автоматически создать сегменты до 10 секунд;
- не обрезать исходный ролик;
- сохранить весь порядок;
- создать continuity contract;
- не спрашивать пользователя о разделении.

---

# 33. UNKNOWN CAPABILITY POLICY

```yaml
status: UNKNOWN
action:
  - avoid unsupported claim
  - write provider-neutral mechanism
  - supply fallback production plan
```

---

# 34. INTERNAL SELF-CHECK

Перед выдачей система проверяет:

```text
Что является игрушкой?
Что является реальным?
Что находится на экране?
Что является отражением?
Каков масштаб каждого объекта?
Какие переходы обязательны?
Где начинается и заканчивается каждый эффект?
Как продолжается движение между сегментами?
Что модель может выполнить нативно?
Что потребует монтажа?
Есть ли неоднозначные слова?
Есть ли противоречия?
```

---

# 35. FINAL VALIDATION CHECKLIST

- [ ] Provider profile создан
- [ ] Возможности модели не выдуманы
- [ ] Визуальная грамматика сохранена
- [ ] Все объекты классифицированы
- [ ] Масштаб зафиксирован
- [ ] Материал зафиксирован
- [ ] Переходы описаны механизмом
- [ ] Эффекты имеют тайминг
- [ ] Сегменты не разрезают критичные действия
- [ ] Continuity Manifest создан
- [ ] Positive locks присутствуют
- [ ] Negative constraints присутствуют
- [ ] Противоречия устранены
- [ ] Similarity validation пройдена
- [ ] QA не содержит BLOCKER
- [ ] QA не содержит CRITICAL
- [ ] Промпты готовы для копирования
- [ ] Обработан весь ролик
- [ ] Пользователь не должен просить продолжение

---

# 36. GOLDEN RULES

1. Один Reconstruction Package компилируется по-разному для разных моделей.
2. Нельзя выдумывать возможности провайдера.
3. Переход описывается через визуальную механику.
4. Критичный эффект нельзя молча удалить.
5. Игрушка всегда получает scale lock и semantic lock.
6. Последний кадр сегмента является контрактом следующего сегмента.
7. Визуальная грамматика оригинала сохраняется.
8. Общие слова заменяются конкретными.
9. Каждый промпт проходит optimization loop.
10. Каждый промпт проходит similarity validation.
11. Каждый промпт проходит final QA.
12. Ошибка CRITICAL блокирует выдачу.
13. Неподдерживаемое действие получает fallback.
14. Source fidelity важнее украшения.
15. Пользователь получает полный результат сразу.

---

# 37. MODULE CONTRACT

```yaml
MODULE:
  id: ARTMania_07_UNIFIED_RECONSTRUCTION_COMPILER
  version: 2.0.0
  combines:
    - Provider Compiler
    - Transition & FX Library
    - Visual Similarity Validator
    - Multi-Clip Continuity Engine
    - Prompt Optimization Loop
    - Reconstruction QA Engine
    - Visual Grammar Engine
  input:
    - reconstruction_package
    - provider_target
    - user_references
  output:
    - compiled_prompts
    - production_plan
    - continuity_manifest
    - similarity_report
    - qa_report
  depends_on:
    - ARTMania_01_CORE_OS
    - ARTMania_02_PROMPT_ENGINE
    - ARTMania_03_QC_PROVIDER_SYSTEM
    - ARTMania_04_INTELLIGENCE_ENGINE
    - ARTMania_05_MODEL_REGISTRY
    - ARTMania_06_SCENE_RECONSTRUCTION_ENGINE
```

---

# END OF MODULE 07
