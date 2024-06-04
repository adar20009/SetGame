package bguspl.set.ex;

import bguspl.set.Env;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.Random;


/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class  Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    private Dealer dealer;
    protected BlockingQueue<Integer> keyQueue;
    protected BlockingQueue<Integer> slotsOwned;
    private boolean frozen; //let it go
    protected int isLegalSet;


    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.dealer = dealer;
        this.keyQueue = new LinkedBlockingQueue<>(env.config.featureSize);
        this.slotsOwned = new LinkedBlockingQueue<Integer>(env.config.featureSize);
        this.frozen = false;
        this.isLegalSet = 0;

    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        if (!human) createArtificialIntelligence();

        while (!terminate) {
            // TODO implement main player loop
            try {
                if (actionKeyQueue(keyQueue.take()) &&  slotsOwned.size() == env.config.featureSize && table.canPlay) {
                    frozen = true;
                    synchronized (this) {
                        dealer.setsToCheck.add(this);
                        try {
                            this.wait();
                        } catch (InterruptedException e) {}
                    }
                    if (isLegalSet == 1)
                        point();
                    else if (isLegalSet == -1)
                        penalty();
                    else if (isLegalSet == 0)
                        frozen = false;
                }
            } catch (InterruptedException e) {}
        }

        if (!human) try { aiThread.join(); } catch (InterruptedException ignored) {}
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {
                // TODO implement player key press simulator
                Random rand = new Random();
                int randomSlot = rand.nextInt(env.config.tableSize);
                keyPressed(randomSlot);
            }
            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;
        if (playerThread != null) {
            playerThread.interrupt();
            try {
                playerThread.join();
            } catch (InterruptedException e) {
            }
        }
        if (aiThread != null) aiThread.interrupt();
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        // TODO implement
//        if (keyQueue.size() < env.config.featureSize && table.slotToCard[slot] != null && !frozen && table.canPlay)
//            keyQueue.offer(slot);

        if (table.slotToCard[slot] != null && !frozen && table.canPlay) {
            try {
                keyQueue.put(slot);
            } catch (InterruptedException e) {}
        }
    }

    private boolean actionKeyQueue(int slot) {
        synchronized (table.slox[slot]) {
            if (slotsOwned.size() < env.config.featureSize) {
                if (slotsOwned.contains(slot)) {
                    slotsOwned.remove((Integer) slot);
                    table.removeToken(id, slot);
                    return true;
                } else if (table.slotToCard[slot] != null) {
                    slotsOwned.add(slot);
                    table.placeToken(id, slot);
                    return true;
                }
            } else if (slotsOwned.size() == env.config.featureSize) {
                if (slotsOwned.contains(slot)) {
                    slotsOwned.remove((Integer) slot);
                    table.removeToken(id, slot);
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        // TODO implement
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        env.ui.setScore(id, ++score);
        frozen = true;
        long remainingMillis = env.config.pointFreezeMillis;
        while (remainingMillis > 0) {
            env.ui.setFreeze(id, remainingMillis);
            try {
                if(remainingMillis > 500)
                    Thread.sleep(500);
                else Thread.sleep(remainingMillis);
            } catch (InterruptedException e) {}
            remainingMillis -= 500;
        }
        env.ui.setFreeze(id, 0);
        frozen = false;
        isLegalSet = 0;
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        // TODO implement
        frozen = true;
        long remainingMillis = env.config.penaltyFreezeMillis;
        while (remainingMillis > 0) {
            env.ui.setFreeze(id, remainingMillis);
            try {
                if(remainingMillis > 500)
                    Thread.sleep(500);
                else Thread.sleep(remainingMillis);
            } catch (InterruptedException e) {}
            remainingMillis -= 500;
        }
        env.ui.setFreeze(id, 0);
        frozen = false;
        isLegalSet = 0;
    }

    public int score() {
        return score;
    }
}
