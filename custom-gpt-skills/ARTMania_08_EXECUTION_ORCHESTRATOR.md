# ARTMania OS v2
## Module 08 — EXECUTION ORCHESTRATOR
### Version: 2.0.0
### Status: Production Architecture

---

# 0. PURPOSE

Модуль превращает сложную реконструкцию видео из одного перегруженного промпта в управляемый производственный конвейер:

```text
SOURCE VIDEO
→ COMPLEXITY CLASSIFICATION
→ SHOT / TRANSITION / FX DECOMPOSITION
→ GENERATION STRATEGY
→ CLEAN SHOT PROMPTS
→ TRANSITION ASSETS
→ FX ASSETS
→ EDIT DECISION LIST
→ ASSEMBLY
→ VALIDATION
→ TARGETED REPAIR
```

Главное правило:

```text
GENERATE SHOTS. COMPOSE TRANSITIONS. ASSEMBLE. VALIDATE.
```

---

# 1. INPUT CONTRACT

```yaml
INPUT:
  source_video:
  reconstruction_package:
  target_provider:
  target_model:
  max_generation_duration:
  aspect_ratio:
  fps:
  user_references:
  replacement_scope:
  postproduction_allowed:
  editing_software:
  output_language:
```

---

# 2. OUTPUT CONTRACT

```yaml
OUTPUT:
  production_mode:
  complexity_report:
  shot_manifest:
  generation_units:
  transition_units:
  effect_units:
  reference_manifest:
  continuity_manifest:
  edit_decision_list:
  assembly_instructions:
  validation_plan:
  repair_plan:
  copy_ready_prompts:
```

---

# 3. PRODUCTION MODE SELECTOR

```yaml
PRODUCTION_MODE:
  SINGLE_GENERATION:
  MULTI_SHOT_GENERATION:
  HYBRID_GENERATION_COMPOSITING:
  FULL_POSTPRODUCTION_RECONSTRUCTION:
```

## SINGLE_GENERATION

Разрешён только если:

- один непрерывный шот;
- одна сцена;
- одно главное действие;
- не более одного движения камеры;
- нет сложного перехода;
- нет смены масштаба объекта;
- нет морфа, screen replacement, точной графики;
- нет нескольких независимых эффектов;
- нет критической синхронизации по кадрам.

## MULTI_SHOT_GENERATION

Для нескольких независимых шотов и обычных склеек.

## HYBRID_GENERATION_COMPOSITING

Для масок, object wipe, вспышек, огня, частиц, screen replacement, логотипов, сложных переходов и графики.

## FULL_POSTPRODUCTION_RECONSTRUCTION

Для роликов, где основная визуальная механика создаётся монтажом и эффектами, а не одной генерацией.

---

# 4. COMPLEXITY CLASSIFIER

```yaml
COMPLEXITY_REPORT:
  shot_count:
  transition_count:
  effect_count:
  location_count:
  subject_count:
  transformation_count:
  camera_move_count:
  rapid_event_count:
  graphics_count:
  exact_timing_dependencies:
  continuity_risk:
  generation_risk:
  recommended_mode:
```

```text
score =
shot_count × 2
+ transition_count × 3
+ effect_count × 2
+ transformation_count × 4
+ location_count × 2
+ graphics_count × 3
+ exact_timing_dependencies × 3
```

```text
0–7   → SINGLE_GENERATION
8–17  → MULTI_SHOT_GENERATION
18–29 → HYBRID_GENERATION_COMPOSITING
30+   → FULL_POSTPRODUCTION_RECONSTRUCTION
```

BLOCKER всегда переопределяет числовой результат.

---

# 5. SINGLE-GENERATION BLOCKERS

SINGLE_GENERATION запрещён, если присутствует хотя бы одно:

- игрушка превращается в реальный объект;
- резкая смена масштаба;
- переход через колесо, глаз, экран или объект;
- масочный переход;
- морф;
- три и более шота;
- два и более сложных эффекта;
- точный логотип или читаемый текст;
- точная музыкальная синхронизация;
- переход короче 0,5 секунды;
- смена окружения во время активного движения;
- критичная склейка в пике blur или flash.

---

# 6. SOURCE DECOMPOSITION

```yaml
UNIT_TYPES:
  CLEAN_SHOT:
  TRANSITION:
  FX_LAYER:
  GRAPHIC_LAYER:
  SCREEN_CONTENT:
  AUDIO_CUE:
  HOLD_FRAME:
  COMPOSITE:
```

Исходник разбивается на отдельные производственные единицы, а не только на сцены.

---

# 7. SHOT MANIFEST

```yaml
SHOT:
  id:
  source_start:
  source_end:
  duration:
  frame_start:
  frame_end:
  scene_id:
  description:
  subjects:
  objects:
  scale_locks:
  material_locks:
  action:
  camera:
  lighting:
  environment:
  entry_state:
  exit_state:
  generation_method:
  reference_requirements:
  continuity_dependencies:
  risk_level:
```

---

# 8. GENERATION UNIT RULE

Одна Generation Unit должна содержать:

- один главный шот;
- одно главное действие;
- одно движение камеры;
- максимум один сложный визуальный механизм;
- стабильную сцену;
- стабильный масштаб;
- стабильную идентичность.

При превышении лимита единица автоматически делится.

```yaml
LIMITS:
  primary_action: 1
  secondary_action: 1
  camera_move: 1
  complex_transition: 0
  major_fx: 1
  location_change: 0
  scale_state: 1
```

---

# 9. CLEAN SHOT POLICY

По умолчанию генерируется чистый материал без монтажных эффектов.

Не добавлять в основной шот:

- вспышку перехода;
- финальный transition blur;
- маску;
- логотип;
- графический текст;
- screen overlay;
- монтажный shake.

Эти элементы создаются отдельными Transition Unit, FX Layer или Graphic Layer.

---

# 10. TRANSITION UNIT

```yaml
TRANSITION_UNIT:
  id:
  source_shot:
  destination_shot:
  start_time:
  end_time:
  duration:
  mechanism:
  visual_anchor:
  screen_direction:
  source_exit:
  peak_state:
  destination_entry:
  required_assets:
  generation_method:
  compositing_method:
  fallback:
```

Один Transition Unit содержит один механизм перехода.

---

# 11. EFFECT UNIT

```yaml
EFFECT_UNIT:
  id:
  type:
  source_start:
  source_peak:
  source_end:
  target_shot:
  target_region:
  motion_tracking:
  blend_mode:
  opacity_curve:
  intensity_curve:
  color_behavior:
  interaction_with_subject:
  native_generation:
  separate_layer:
  postproduction:
```

Эффект выносится отдельно, если:

- перекрывает более 25% кадра;
- должен начаться или закончиться в точном кадре;
- связан с маской;
- скрывает склейку;
- повторяется;
- должен выглядеть одинаково в нескольких шотах;
- содержит текст или логотип;
- модель нестабильно меняет его форму.

---

# 12. OBJECT SCALE ISOLATION

Игрушечная и реальная версия объекта считаются разными сущностями.

```yaml
ENTITY:
  toy_car_A:
    class: TOY
    scale: palm-sized
    material: glossy plastic

  real_car_A:
    class: REAL_FULL_SIZE
    scale: production vehicle
    material: painted metal
```

Запрещено помещать обе версии в один clean-shot prompt.

---

# 13. TRANSFORMATION POLICY

Любая сложная трансформация делится на три части:

```text
SOURCE STATE
→ TRANSFORMATION BRIDGE
→ DESTINATION STATE
```

1. Чистый исходный объект.
2. Короткий bridge с blur, flash, mask или morph.
3. Чистый конечный объект.

Если генеративный морф ненадёжен, bridge создаётся монтажом.

---

# 14. REFERENCE MANIFEST

```yaml
REFERENCE_MANIFEST:
  generation_unit:
  first_frame:
  last_frame:
  identity_reference:
  wardrobe_reference:
  object_reference:
  environment_reference:
  composition_reference:
  motion_reference:
  forbidden_reference_mix:
```

Нельзя смешивать в одном clean-shot prompt:

- игрушечный и реальный объект;
- разные костюмы;
- разные локации;
- источник и назначение сложного перехода;
- дневную и ночную сцену.

---

# 15. FIRST/LAST FRAME CONTRACT

```yaml
FRAME_CONTRACT:
  required_start_frame:
  required_end_frame:
  subject_pose:
  object_positions:
  camera_position:
  focal_length_behavior:
  motion_vector:
  lighting_direction:
  active_effects:
```

Следующий шот обязан принять предыдущий end state или использовать исходный переход, скрывающий различия.

---

# 16. CONTINUITY MASK

Если точная непрерывность невозможна, граница скрывается только механизмом, соответствующим оригиналу:

- foreground occlusion;
- full-frame blur;
- white flash;
- black frame;
- whip pan;
- smoke coverage;
- wheel close-up;
- screen fill;
- motion graphic;
- sound-driven hard cut.

---

# 17. EDIT DECISION LIST

```yaml
EDL:
  project_fps:
  tracks:
    V1: clean_shots
    V2: transition_layers
    V3: fx_layers
    V4: graphics
    V5: adjustment_layers
    A1: source_audio
    A2: sound_effects
  events:
    - event_id:
      source:
      source_in:
      source_out:
      timeline_in:
      timeline_out:
      transition:
      speed:
      opacity:
      notes:
```

---

# 18. FRAME-ACCURATE TIMING

Внутренний тайминг ведётся в кадрах.

```yaml
TIMING:
  fps:
  start_frame:
  end_frame:
  duration_frames:
  duration_seconds:
```

```text
time_seconds = frame_number / fps
```

Приоритет:

```text
frames > rounded seconds
```

---

# 19. SPEED RAMP MAP

```yaml
SPEED_RAMP:
  shot_id:
  points:
    - frame:
      speed_percent:
      interpolation:
```

Нельзя описывать speed ramp только словами “fast then slow”.

---

# 20. AUDIO SYNC MAP

```yaml
AUDIO_CUE:
  id:
  frame:
  time:
  cue_type:
  linked_visual_event:
  tolerance_frames:
```

Ключевые склейки, вспышки, удары, появления и логотип привязываются к audio cue.

---

# 21. GENERATION PROMPT TEMPLATE

```text
GENERATION UNIT [ID]
DURATION: [X seconds]
MODE: CLEAN SHOT

Create only this single shot.

START FRAME:
[exact visual state]

SCENE:
[environment]

SUBJECT:
[identity, clothing, pose]

OBJECTS:
[class, scale, material, position]

ACTION TIMELINE:
[time-coded action]

CAMERA:
[shot size, angle, motion, speed]

LIGHTING:
[light direction and quality]

END FRAME:
[exact required final state]

POSITIVE LOCKS:
[critical requirements]

NEGATIVE CONSTRAINTS:
[forbidden alternatives]

DO NOT ADD:
[transitions, extra effects, new objects, text, logo]
```

---

# 22. TRANSITION PROMPT TEMPLATE

```text
TRANSITION UNIT [ID]

SOURCE EXIT:
[exact end state]

DESTINATION ENTRY:
[exact start state]

MECHANISM:
[frame-by-frame visual behavior]

TIMING:
[start, peak, end]

DIRECTION:
[screen direction]

OCCLUSION / BLUR / FLASH:
[exact behavior]

CUT POINT:
[exact hidden frame]

CONTINUITY:
[what must match]

NEGATIVE CONSTRAINTS:
[what must not change]
```

---

# 23. POSTPRODUCTION TEMPLATE

```text
POST UNIT [ID]

1. Place source shot on V1.
2. Place destination shot after it.
3. Apply the specified transition over [N] frames.
4. Track [visual anchor].
5. Animate [effect parameter].
6. Place the cut at [peak state].
7. Match exposure, blur and motion direction.
8. Validate against source frames [A–B].
```

---

# 24. ASSEMBLY ORDER

```text
1. Generate all clean shots
2. Select best takes
3. Normalize resolution and fps
4. Trim to exact frame boundaries
5. Assemble hard cuts
6. Add transition units
7. Add FX layers
8. Add graphics
9. Add speed ramps
10. Sync audio cues
11. Match color
12. Validate
13. Repair only failed ranges
```

---

# 25. TAKE SELECTION

```yaml
TAKE_SCORE:
  identity:
  composition:
  scale:
  action:
  camera:
  end_frame:
  continuity:
  artifacts:
  total:
```

Красивый клип не выбирается, если он хуже совпадает с исходником.

---

# 26. VALIDATION PIPELINE

Сравниваются SOURCE VIDEO и ASSEMBLED RESULT по критериям:

- длительность;
- количество шотов;
- границы шотов;
- композиция;
- масштаб;
- положение объектов;
- движение камеры;
- движение персонажа;
- переходы;
- эффекты;
- свет;
- ритм;
- графика;
- аудиосинхронизация.

---

# 27. ERROR LOCALIZATION

```yaml
ERROR:
  id:
  start_frame:
  end_frame:
  affected_unit:
  category:
  severity:
  source_expected:
  result_observed:
  repair_action:
```

---

# 28. TARGETED REPAIR POLICY

Запрещено перегенерировать весь ролик при локальной ошибке.

Ремонтируется только:

- один шот;
- несколько кадров перехода;
- один FX layer;
- один speed ramp;
- один graphic layer;
- одна continuity boundary.

```yaml
REPAIR_MODE:
  REGENERATE_SHOT:
  REGENERATE_END_FRAME:
  REGENERATE_START_FRAME:
  REBUILD_TRANSITION:
  REPLACE_FX_LAYER:
  RETIME:
  REFRAME:
  COLOR_MATCH:
  COMPOSITE_PATCH:
```

---

# 29. LONG VIDEO POLICY

Для длинного ролика:

- строится полный master timeline;
- создаются все шоты;
- шоты группируются в производственные пакеты;
- итоговая длительность сохраняется;
- система не останавливается после первой части;
- система не спрашивает “продолжить?”.

---

# 30. PROVIDER LIMIT POLICY

Если модель поддерживает максимум 10 секунд, это не означает, что каждый сегмент должен быть 10 секунд.

```text
simple static shot: 5–10 s
controlled action: 3–6 s
complex camera move: 2–4 s
transformation bridge: 0.5–2 s
transition plate: 0.3–1.5 s
```

---

# 31. SOURCE FIDELITY POLICY

В режиме EXACT RECONSTRUCTION запрещено:

- добавлять сцены;
- изменять порядок;
- заменять переход;
- менять игрушку на реальный объект;
- менять реальный объект на игрушку;
- менять финал;
- добавлять модный эффект;
- улучшать композицию;
- менять монтажный ритм;
- переносить события на другое время.

---

# 32. REPLACEMENT SCOPE

При замене персонажа:

```yaml
REPLACE:
  identity: true

PRESERVE:
  body_action: true
  camera: true
  timing: true
  environment: true
  transitions: true
  effects: true
  shot_order: true
```

При замене одежды меняется только одежда.

---

# 33. AUTOMATIC USER OUTPUT

Система должна выдать:

```text
PRODUCTION MODE
COMPLEXITY REPORT
SHOT LIST
GENERATION PROMPTS
TRANSITION PROMPTS
FX INSTRUCTIONS
EDIT TIMELINE
CONTINUITY NOTES
REPAIR PLAN
```

По умолчанию внутренние YAML-таблицы пользователю не показываются.

---

# 34. HARD FAIL CONDITIONS

Статус `PRODUCTION_PLAN_INVALID`, если:

- не обработан весь исходник;
- отсутствует хотя бы один шот;
- сложный ролик выдан одним промптом;
- потерян transition unit;
- эффект молча удалён;
- нет EDL;
- нет continuity boundary;
- игрушка и реальный объект смешаны;
- невозможно понять порядок сборки;
- результат нельзя проверить по кадрам.

---

# 35. FINAL QA CHECKLIST

- [ ] Production Mode выбран
- [ ] Complexity Report создан
- [ ] SINGLE_GENERATION не используется при blocker
- [ ] Весь ролик разбит на units
- [ ] Каждый clean shot изолирован
- [ ] Переходы вынесены отдельно
- [ ] Эффекты классифицированы
- [ ] Масштаб объектов разделён
- [ ] Transformation bridge создан
- [ ] First/Last Frame Contract создан
- [ ] Continuity Manifest создан
- [ ] EDL создан
- [ ] Тайминг задан в кадрах
- [ ] Audio cues привязаны
- [ ] Prompts готовы для копирования
- [ ] Assembly order понятен
- [ ] Validation plan создан
- [ ] Repair plan локальный
- [ ] Нет HARD FAIL
- [ ] Обработан весь ролик

---

# 36. GOLDEN RULES

1. Сложный ролик — это производственный проект, а не один промпт.
2. Один шот генерируется отдельно.
3. Один сложный переход создаётся отдельно.
4. Монтажные эффекты не перегружают clean-shot prompt.
5. Игрушечный и реальный объект — разные сущности.
6. Масштаб нельзя менять внутри одной generation unit без bridge.
7. Переход должен скрывать реальные различия между клипами.
8. Тайминг ведётся в кадрах.
9. Last frame одного шота — контракт следующего.
10. Ошибка ремонтируется локально.
11. Красивый результат не равен точной копии.
12. Exact Reconstruction важнее художественной свободы.
13. Неподдерживаемый эффект собирается в посте.
14. Нельзя молча удалить сложный элемент.
15. Пользователь получает полный план сборки сразу.

---

# 37. MODULE CONTRACT

```yaml
MODULE:
  id: ARTMania_08_EXECUTION_ORCHESTRATOR
  version: 2.0.0
  role:
    - production_mode_selection
    - shot_decomposition
    - transition_decomposition
    - fx_decomposition
    - prompt_generation
    - edit_timeline_creation
    - continuity_management
    - validation
    - targeted_repair
  depends_on:
    - ARTMania_01_CORE_OS
    - ARTMania_02_PROMPT_ENGINE
    - ARTMania_03_QC_PROVIDER_SYSTEM
    - ARTMania_04_INTELLIGENCE_ENGINE
    - ARTMania_05_MODEL_REGISTRY
    - ARTMania_06_SCENE_RECONSTRUCTION_ENGINE
    - ARTMania_07_UNIFIED_RECONSTRUCTION_COMPILER
  input:
    - source_video
    - reconstruction_package
    - provider_profile
  output:
    - production_mode
    - generation_units
    - transition_units
    - effect_units
    - edit_decision_list
    - copy_ready_prompts
    - validation_plan
    - repair_plan
```

---

# END OF MODULE 08
