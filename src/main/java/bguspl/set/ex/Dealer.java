package bguspl.set.ex;

import bguspl.set.Env;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;
import java.util.stream.IntStream;


/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    protected BlockingQueue<Player> setsToCheck;
    private Player p;
    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        setsToCheck = new LinkedBlockingQueue<Player>();
        reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
        Collections.shuffle(deck);
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        for (int i = 0; i < env.config.players; i++) {
            Thread curr = new Thread(players[i], env.config.playerNames[i]);
            curr.start();
        }
        while (!shouldFinish()) {
            placeCardsOnTable();
            table.canPlay = true;
            if (env.config.hints)
                table.hints();
            updateTimerDisplay(true);
            timerLoop();
            updateTimerDisplay(true);
            table.canPlay = false;
            removeAllCardsFromTable();
            Collections.shuffle(deck);
        }
        announceWinners();
        for(int i = env.config.players - 1; i >= 0; i--) {
            players[i].terminate();
        }
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            removeCardsFromTable();
            placeCardsOnTable();
        }
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        // TODO implement
        terminate = true;
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() { return terminate || env.util.findSets(deck, 1).size() == 0; }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        // TODO implement
        if (p != null) {
            int[] playerCards = p.slotsOwned.stream().mapToInt(i -> table.slotToCard[i]).toArray();
//            int[] playerCards = new int[p.slotsOwned.size()];
//            for(int i = 0; i < playerCards.length; i++) {
//                Integer temp = p.slotsOwned.poll();
//                playerCards[i] = table.slotToCard[temp];
//                p.slotsOwned.add(temp);
//            }
            boolean isLegalSet = env.util.testSet(playerCards);
            if (isLegalSet) {
                for (int i = 0; i < playerCards.length; i++) {
                    int slot = table.cardToSlot[playerCards[i]];
                    synchronized (table.slox[slot]) {
                        for (Player currP : players) {
                            if (currP.slotsOwned.contains(slot)) {
                                currP.slotsOwned.remove(slot);
                                table.removeToken(currP.id, slot);
                            }
                            if(currP.keyQueue.contains(slot)){
                                currP.keyQueue.remove(slot);
                            }
                        }
                        for (Player currSet : setsToCheck) {
                            if (currSet.slotsOwned.size() < env.config.featureSize) {
                                setsToCheck.remove(currSet);
                                synchronized (currSet) {
                                    currSet.notify();
                                }
                            }
                        }
                        table.removeCard(slot);
                    }
                }
                if (env.config.hints) {
                    table.hints();
                }
                p.isLegalSet = 1;
                synchronized (p) {
                    p.notify();
                }
                updateTimerDisplay(true);
            } else {
                p.isLegalSet = -1;
                synchronized (p) {
                    p.notify();
                }
            }
        }
    }


    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        for (int i = 0; i < env.config.tableSize; i++)
            if (table.slotToCard[i] == null && !deck.isEmpty())
                table.placeCard(deck.remove(0), i);
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        // TODO implement
        long timeLeft = reshuffleTime - System.currentTimeMillis();
        if (timeLeft >= env.config.turnTimeoutWarningMillis) {
            try {
                p = setsToCheck.poll(timeLeft % 1000, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {}
        } else {
            p = setsToCheck.poll();
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        // TODO implement
        if (!reset) {
            long newTime = reshuffleTime - System.currentTimeMillis();
            env.ui.setCountdown(newTime, newTime < env.config.turnTimeoutWarningMillis);
        }
        else {
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
            env.ui.setCountdown(env.config.turnTimeoutMillis, false);
        }
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    public void removeAllCardsFromTable() {
        // TODO implement
        for (Player p : players) {
            p.keyQueue.clear();
            p.slotsOwned.clear();
        }
        for (int i = 0; i < env.config.tableSize; i++)
            synchronized (table.slox[i]) {
                if (table.slotToCard[i] != null) {
                    deck.add(table.slotToCard[i]);
                    table.removeCard(i);
                }
            }
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        // TODO implement
        int maxScore = 0;
        List<Player> winners = new LinkedList<>();
        for (Player p : players) {
            if (p.score() > maxScore) {
                maxScore = p.score();
                winners.clear();
                winners.add(p);
            }
            else if (p.score() == maxScore)
                winners.add(p);
        }
        env.ui.announceWinner(winners.stream().mapToInt(p -> p.id).toArray());
    }
}
