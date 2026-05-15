package com.bg7yoz.ft8cn.decisions;
/**
 * DecisionEngine: центральный компонент машины состояний.
 * Принимает решения на основе контекста и возвращает действие (StationAction).
 *
 * Принцип: чистая функция. Вход: DecisionContext + список сообщений + DatabaseOpr.
 * Выход: StationAction. Никаких побочных эффектов, никакого доступа к железу.
 *
 * Все решения логируются с тегом [DECISION] для отладки и анализа поведения.
 *
 * @author BG7YOZ
 * @date 2026-05-10
 */

import android.database.Cursor; // Для прямого SQL-запроса
import android.util.Log; // Для логирования решений

import com.bg7yoz.ft8cn.Ft8Message; // Импорт класса сообщения для извлечения параметров
import com.bg7yoz.ft8cn.GeneralVariables; // [NEW] Импорт для определения шага по тексту сообщения
import com.bg7yoz.ft8cn.database.DatabaseOpr; // Импорт для доступа к записям станций

import java.util.ArrayList; // Для работы со списками кандидатов
import java.util.List; // Общий импорт списков
import java.util.HashSet; // Для кэша отработанных позывных
import java.util.Set; // Для кэша отработанных позывных

public class DecisionEngine {
    // Тег для логирования, используется во всех Log.d вызовах этого класса
    private static final String TAG = "DecisionEngine";

    // Экземпляр скорера для оценки приоритета станций (вынесен в поле для переиспользования)
    private final HybridScorer scorer = new HybridScorer();

    // [NEW] Кэш позывных, которые уже есть в QSL Log на ТЕКУЩЕМ бэнде
    private final Set<String> workedCallsignsCache = new HashSet<>();
    private long cachedBandFreq = 0; // Чтобы сбрасывать кэш при смене бэнда

    /**
     * [NEW] Сброс кэша QSL при смене частоты или очистке истории
     * Вызывается из MainViewModel.resetStateMachineOnFreqChange()
     */
    public void resetCache() {
        workedCallsignsCache.clear();
        cachedBandFreq = 0;
        Log.d(TAG, "[DECISION] Cache reset: freq changed / history cleared");
    }

    /**
     * [STATE MACHINE] Centralized override lifecycle management.
     * Called at the beginning of every decision cycle.
     * Clears override when retries exhausted, target cleared, or target becomes invalid.
     *
     * NOTE: Protocol step completion is handled by MainViewModel state transitions.
     * This method focuses on breaking deadlocks (max retries, ghost targets).
     */
    private boolean evaluateOverrideLifecycle(DecisionContext ctx) {
        if (!ctx.userOverrideActive) return false;

        boolean shouldClear = false;
        String reason = "";

        // 1. Max retries reached (primary signal that dialogue failed)
        if (GeneralVariables.noReplyLimit > 0 && ctx.noReplyCount >= GeneralVariables.noReplyLimit) {
            shouldClear = true;
            reason = "Max retries reached (" + ctx.noReplyCount + ")";
        }
        // 2. Target callsign cleared externally
        else if (ctx.currentTarget == null || ctx.currentTarget.isEmpty()) {
            shouldClear = true;
            reason = "Target cleared";
        }
        // 3. [GHOST TARGET] Target disappeared from visible stations
        else if (ctx.currentTarget != null && !ctx.currentTarget.isEmpty() && ctx.visibleStations != null) {
            boolean targetVisible = false;
            for (DatabaseOpr.StationRecord s : ctx.visibleStations) {
                if (s.callsign.equals(ctx.currentTarget)) {
                    targetVisible = true;
                    break;
                }
            }
            // If target gone AND we already tried calling it (noReplyCount > 0) → abort
            if (!targetVisible && ctx.noReplyCount > 0) {
                shouldClear = true;
                reason = "Target disappeared from waterfall";
            }
        }

        if (shouldClear) {
            // Note: We do NOT modify ctx here because DecisionContext is a snapshot.
            // We return true, and the caller (MainViewModel) will reset the actual StationContext.
            Log.d(TAG, "[OVERRIDE] Auto-clear conditions met: " + reason);
            return true;
        }
        return false;
    }

    /**
     * Главный метод оценки: принимает контекст, список декодированных сообщений и доступ к БД,
     * возвращает действие для исполнения.
     *
     * [FSM ENTRY POINT] Точка входа в машину состояний.
     *
     * @param ctx Контекст решения
     * @param messages Список сообщений
     * @param db Экземпляр DatabaseOpr
     * @return StationAction
     */
    public StationAction evaluate(DecisionContext ctx, List<Ft8Message> messages, DatabaseOpr db) {
        // [P0] Экстренная остановка
        if (ctx.emergencyStop) {
            Log.d(TAG, "[DECISION] emergencyStop=true → ABORT");
            return StationAction.abort("Emergency stop triggered");
        }

        // [P0.5] [NEW] Приоритет: отправка собственного CQ после сброса
        // Если forceOwnCQ=true, игнорируем всё остальное и начинаем с собственного CQ
        if (ctx.forceOwnCQ) {
            Log.d(TAG, "[DECISION] forceOwnCQ=true → TX_OWN_CQ");
            return StationAction.txOwnCQ();
        }

        // [STATE MACHINE] Evaluate override state FIRST
        boolean overrideShouldClear = evaluateOverrideLifecycle(ctx);

        // If override should be cleared, return ABORT so MainViewModel can reset the real context
        if (overrideShouldClear) {
            return StationAction.abort("Override cleared by state machine");
        }

        // [P1] Ручной выбор пользователя (после проверки lifecycle)
        if (ctx.userOverrideActive && ctx.currentTarget != null && !ctx.currentTarget.isEmpty()) {
            Log.d(TAG, "[DECISION] userOverrideActive=true → TRANSMIT to " + ctx.currentTarget);
            Ft8Message msg = findMessageByCallsign(messages, ctx.currentTarget);
            return createTransmitAction(ctx.currentTarget, 1, "Manual override", msg);
        }

        // [P2] Прямой вызов НАМ
        // [FIX] Гибридная логика: "Первый раз -> База, Второй раз (повтор) -> Сообщение".
        if (ctx.directCaller != null) {
            Ft8Message msg = findMessageByCallsign(messages, ctx.directCaller.callsign);
            if (msg != null) {
                // 1. Получаем состояние из базы (Наша память)
                int dbState = ctx.directCaller.ft8StateRelative;

                // 2. Получаем состояние из сообщения (Реальность)
                int msgState = GeneralVariables.checkFunOrder(msg);
                if (msgState == -1) msgState = 0; // Защита от мусора

                int nextStep;
                boolean isRepeat = (dbState > msgState && msgState > 0);

                if (isRepeat) {
                    // === Логика для "Второго раза" (Repeat Request) ===
                    // Если база говорит, что мы дальше, чем сообщение (например База=3, Msg=2),
                    // значит станция шлет повтор. Отвечаем исходя из сообщения.
                    nextStep = msgState + 1;
                    Log.d(TAG, "[DECISION] directCaller=" + ctx.directCaller.callsign +
                            " REPEAT detected (DB=" + dbState + " Msg=" + msgState +
                            ") → Answering based on MSG → step=" + nextStep);
                } else {
                    // === Логика для "Первого раза" ===
                    // Если база равна сообщению или отстает (База=0 или База=1, Msg=2).
                    // Отвечаем исходя из базы, как просил.
                    // ИСКЛЮЧЕНИЕ: Если база 0 (новая станция), берем за основу msg, чтобы начать диалог.
                    if (dbState == 0 || msgState > dbState) {
                        nextStep = msgState + 1;
                        Log.d(TAG, "[DECISION] directCaller=" + ctx.directCaller.callsign +
                                " NEW/SYNC (DB=" + dbState + " Msg=" + msgState +
                                ") → Answering based on MSG to sync → step=" + nextStep);
                    } else {
                        nextStep = dbState + 1;
                        Log.d(TAG, "[DECISION] directCaller=" + ctx.directCaller.callsign +
                                " FIRST TIME (DB=" + dbState + " Msg=" + msgState +
                                ") → Answering based on DB → step=" + nextStep);
                    }
                }

                // Ограничиваем шаг диапазоном протокола
                if (nextStep < 1) nextStep = 1;
                if (nextStep > 5) nextStep = 5;

                return createTransmitAction(ctx.directCaller.callsign, nextStep, "Direct call detected", msg);
            } else {
                Log.w(TAG, "[DECISION] directCaller=" + ctx.directCaller.callsign + " but msg not in current decode");
            }
        }

        // [STATE DISPATCH]
        switch (ctx.subState) {
            case SEEKING:
                return evaluateSeeking(ctx, messages, db);
            case IN_DIALOGUE:
                return evaluateInDialogue(ctx, messages);
            case SOFT_FINISH:
                return evaluateSoftFinish(ctx, messages);
            case NOMADIC:
                return evaluateNomadic(ctx);
            default:
                Log.d(TAG, "[DECISION] subState=" + ctx.subState + " not implemented → WAIT");
                return StationAction.wait("State " + ctx.subState + " not fully implemented");
        }
    }

    /**
     * Поиск сообщения по позывному
     */
    private Ft8Message findMessageByCallsign(List<Ft8Message> messages, String callsign) {
        if (messages == null || callsign == null) return null;
        for (Ft8Message m : messages) {
            if (m.getCallsignFrom().equals(callsign)) {
                return m;
            }
        }
        return null;
    }

    /**
     * Создание действия TRANSMIT
     */
    private StationAction createTransmitAction(String callsign, int step, String reason, Ft8Message msg) {
        if (msg != null) {
            Log.d(TAG, "[DECISION] Creating TRANSMIT with freq=" + msg.freq_hz + " snr=" + msg.snr);
            return StationAction.transmit(callsign, step, reason,
                    (long) msg.freq_hz, msg.snr, msg.i3, msg.n3, msg.extraInfo);
        } else {
            Log.w(TAG, "[DECISION] No message for " + callsign + ", using default params");
            return StationAction.transmit(callsign, step, reason);
        }
    }

    /**
     * Проверка QSL Log
     */
    private boolean isCallsignWorkedOnBand(String callsign, long bandFreq, DatabaseOpr db) {
        if (bandFreq != cachedBandFreq) {
            workedCallsignsCache.clear();
            cachedBandFreq = bandFreq;
        }
        String cacheKey = callsign + "@" + bandFreq;
        if (workedCallsignsCache.contains(cacheKey)) return true;

        boolean isWorked = false;
        try {
            Cursor cursor = db.getDb().rawQuery(
                    "SELECT ID FROM QslCallsigns WHERE callsign=? LIMIT 1",
                    new String[]{callsign}
            );
            isWorked = (cursor.getCount() > 0);
            cursor.close();
        } catch (Exception e) {
            Log.e(TAG, "[QSL] Direct query failed: " + e.getMessage());
        }

        if (isWorked) {
            workedCallsignsCache.add(cacheKey);
        }
        return isWorked;
    }

    /**
     * Оценщик SEEKING
     */
    private StationAction evaluateSeeking(DecisionContext ctx, List<Ft8Message> messages, DatabaseOpr db) {
        Log.d(TAG, "[DEBUG] visibleStations count: " + ctx.visibleStations.size());

        // === [FIX] GHOST TARGET CHECK ===
        // Если у нас есть цель вызова (currentTarget), но она ПРОПАЛА из видимых станций
        // (не декодируется), мы должны прекратить её звать, чтобы не передавать "в пустоту".
        if (ctx.currentTarget != null && !ctx.currentTarget.isEmpty()) {
            boolean isTargetVisible = false;
            for (DatabaseOpr.StationRecord s : ctx.visibleStations) {
                if (s.callsign.equals(ctx.currentTarget)) {
                    isTargetVisible = true;
                    break;
                }
            }

            // Если цель пропала И мы уже пытались её позвать (noReplyCount > 0)
            // то нужно прервать и дать системе найти новую станцию (например SP7BCA).
            // Оставляем 1 слот "запаса" на случай QSB (замирания), но если он ушел надолго - ABORT.
            if (!isTargetVisible && ctx.noReplyCount > 0) {
                Log.d(TAG, "[DECISION] Target " + ctx.currentTarget + " DISAPPEARED (not in visibleStations). ABORT.");
                return StationAction.abort("Target disappeared from waterfall");
            }
        }
        // === [END FIX] ===

        // === [CRITICAL FIX] Если у нас уже есть currentTarget (мы кого-то вызвали),
        // мы НЕ ищем новые CQ. Мы ждем ответа от currentTarget.
        // Это предотвращает "переключение" на других станций, пока мы в диалоге.
        if (ctx.currentTarget != null && !ctx.currentTarget.isEmpty()) {
            // Проверяем, видна ли наша цель в текущем декоде
            boolean targetSeen = false;
            for (DatabaseOpr.StationRecord s : ctx.visibleStations) {
                if (s.callsign.equals(ctx.currentTarget)) {
                    targetSeen = true;
                    break;
                }
            }
            // Если цель видна — ждем её ответа, не ищем других
            if (targetSeen) {
                Log.d(TAG, "[DECISION] SEEKING: Waiting for reply from " + ctx.currentTarget + ", ignoring other CQs");
                return StationAction.wait("Waiting for target reply");
            }
        }

        // Фильтрация CQ-кандидатов (только если currentTarget пустой)
        List<DatabaseOpr.StationRecord> cqCandidates = new ArrayList<>();
        for (DatabaseOpr.StationRecord s : ctx.visibleStations) {
            if (s.ft8StateRelative == 6) {
                // 1. Пропускаем, если уже работали (проверка по логу)
                if (isCallsignWorkedOnBand(s.callsign, com.bg7yoz.ft8cn.GeneralVariables.band, db)) {
                    Log.d(TAG, "[DECISION] SKIP " + s.callsign + " (already in QSL log)");
                    continue;
                }

                // 2. [CRITICAL FIX] Пропускаем, если нет свежего сообщения в текущем декоде
                // Без сообщения мы не сможем сформировать валидный пакет (freq/snr/hash будут нулевыми)
                if (messages == null || findMessageByCallsign(messages, s.callsign) == null) {
                    Log.d(TAG, "[DECISION] SKIP " + s.callsign + " (no fresh message in current decode)");
                    continue;
                }

                cqCandidates.add(s);
            }
        }

        Log.d(TAG, "[DECISION] SEEKING: found " + cqCandidates.size() + " CQ candidates (after QSL + fresh msg filter)");

        if (!cqCandidates.isEmpty() && ctx.autoFollowCQ) {
            DatabaseOpr.StationRecord best = scorer.findBestCandidate(cqCandidates, ctx);
            if (best != null) {
                Log.d(TAG, "[DECISION] SEEKING: selected best CQ=" + best.callsign + " score=" + scorer.score(best, ctx));
                // Теперь сообщение гарантированно найдётся (проверено выше)
                Ft8Message msg = findMessageByCallsign(messages, best.callsign);
                return createTransmitAction(best.callsign, 1, "Best CQ by scoring", msg);
            }
        }

        Log.d(TAG, "[DECISION] SEEKING: no suitable CQ → WAIT");
        return StationAction.wait("No suitable CQ found, waiting");
    }

    /**
     * Оценщик IN_DIALOGUE
     */
    private StationAction evaluateInDialogue(DecisionContext ctx, List<Ft8Message> messages) {
        // Лимит попыток
        int maxAttempts = ctx.noReplyLimit > 0 ? ctx.noReplyLimit : 5;
        if (ctx.noReplyCount >= maxAttempts) {
            Log.w(TAG, "[DECISION] IN_DIALOGUE: noReplyCount=" + ctx.noReplyCount + " >= " + maxAttempts + " → ABORT");
            return StationAction.abort("Max attempts (" + maxAttempts + ") reached");
        }

        // Тайминг слотов
        if (ctx.currentTarget != null && ctx.directCaller == null) {
            DatabaseOpr.StationRecord target = DatabaseOpr.getStationRecord(ctx.currentTarget);
            if (target != null && target.lastSeenUtcSec > 0) {
                long targetSlot = target.lastSeenUtcSec / 15;
                if ((ctx.currentSlot - targetSlot) % 2 == 0) {
                    Log.d(TAG, "[DECISION] IN_DIALOGUE: waiting for alternating slot (current=" + ctx.currentSlot + ", targetLast=" + targetSlot + ")");
                    return StationAction.wait("Waiting for alternating slot");
                }
            }
        }

        Log.d(TAG, "[DECISION] IN_DIALOGUE: continuing dialogue with " + ctx.currentTarget);
        return StationAction.wait("Awaiting reply in dialogue");
    }

    /**
     * Оценщик SOFT_FINISH
     */
    private StationAction evaluateSoftFinish(DecisionContext ctx, List<Ft8Message> messages) {
        for (DatabaseOpr.StationRecord s : ctx.visibleStations) {
            if (ctx.recentTargets.contains(s.callsign) && s.ft8StateRelative >= 1 && s.ft8StateRelative <= 4) {
                Log.d(TAG, "[DECISION] SOFT_FINISH: recent target " + s.callsign + " called → RESUME");
                Ft8Message msg = findMessageByCallsign(messages, s.callsign);
                return createTransmitAction(s.callsign, 1, "Recent target called", msg);
            }
        }

        if (ctx.currentSlot - ctx.lastReplySlot > 8) {
            Log.d(TAG, "[DECISION] SOFT_FINISH: timeout → NO_OP");
            return StationAction.noOp("Soft finish timeout");
        }

        Log.d(TAG, "[DECISION] SOFT_FINISH: listening for resume");
        return StationAction.wait("Listening for resume");
    }

    /**
     * Оценщик NOMADIC
     */
    private StationAction evaluateNomadic(DecisionContext ctx) {
        if (ctx.noReplyCount >= 3) {
            Log.d(TAG, "[DECISION] NOMADIC: noReplyCount >= 3 → NOMADIC_SWITCH");
            return StationAction.nomadicSwitch(0, "Nomadic switch trigger");
        }
        Log.d(TAG, "[DECISION] NOMADIC: monitoring current frequency");
        return StationAction.wait("Monitoring frequency");
    }
}