package bguspl.set.ex;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import bguspl.set.Env;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

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

    /*
     * The dealer of the game
     */

    private Dealer dealer;

    /*
     * The Queue of slots the player chose on the board 
     */

    protected volatile BlockingQueue<Integer> choices;
    
    /*
     * Boolean indicationg if the set that I sent was Checked
     */
     protected volatile AtomicBoolean wasChecked;

     /*
      * Boolean indicating if the Set I sent was a good one or a bad one
      */
    protected volatile AtomicBoolean wasPenalized;

    /*
     * Boolean idicating if Im free for clicks on the Table
     */
    protected volatile AtomicBoolean isFree;

    /*
     * Boolean indicationg if i need to go to lock down Protocol because the table isnt ready for players
     */
    protected volatile AtomicBoolean tableReady;

    /*
     * Table got restarted
     */
    protected volatile AtomicBoolean wasShuffled;

    /*
     * List for the AI thread to Choose from
     */
    protected LinkedList<Integer> AIpool = null;

    protected volatile AtomicBoolean aiRun;
    

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
        choices = new LinkedBlockingQueue<Integer>();
        wasChecked = new AtomicBoolean(false);
        wasPenalized = new AtomicBoolean(false);
        isFree = new AtomicBoolean(true);
        tableReady = new AtomicBoolean(true);
        wasShuffled = new AtomicBoolean(false);
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("Thread " + Thread.currentThread().getName() + " starting.");
        if (!human)
        {
            AIpool = new LinkedList<Integer>();
            for(int i = 0; i < env.config.rows*env.config.columns; i++)
                AIpool.add(i);
            aiRun = new AtomicBoolean(false);
            createArtificialIntelligence();
        }
        while (!terminate)
        {
            checkIfTableReady();
            if(terminate)
                break;
            waitForSet();
            waitForRespond();
            if((choices.size() < env.config.featureSize & !wasPenalized.get()) || (choices.size() == env.config.featureSize & wasPenalized.get()) & !tableReady.get() )
                continue;
            updatePenaltytime();
        }
        if (!human) try { aiThread.join(); } catch (InterruptedException ignored) {}
        synchronized(isFree)
        {
            isFree.set(true);
            while(isFree.get())
            {
                try
                {
                    isFree.wait();
                }catch(InterruptedException ignored){}
            }
        }
        env.logger.info("Thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("Thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate)
            {
                synchronized(aiRun)
                {
                    while(choices.peek() != null)
                        keyPressed(choices.peek());
                    Collections.shuffle(AIpool);
                    int cards = table.countCards();
                    while(cards < AIpool.size() & dealer.deck.isEmpty())
                        AIpool.remove(AIpool.size()-1);
                    for(int j = 0; j < env.config.featureSize; j++)
                        keyPressed(AIpool.get(j));
                    aiRun.set(false);
                    while(!aiRun.get())
                    {
                        try
                        {
                            aiRun.wait();
                        } catch(InterruptedException ignored){}
                    }
                   
                }
            }
            env.logger.info("Thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot)
    {
        if(table.slotToCard[slot] != null && isFree.get())
        {
            synchronized(choices)
            {
                if(choices.contains(slot))
                {
                    choices.remove(slot);
                    env.ui.removeToken(id, slot);
                }
                else
                {
                    if(choices.size() < env.config.featureSize)
                    {
                        choices.add(slot);
                        env.ui.placeToken(id, slot);
                    }
                }
                wasPenalized.set(false);
                choices.notifyAll();
            }
        }
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point()
    {
        score++;
        env.ui.setScore(id, score);
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        wasPenalized.set(true);
    }

    public int score() {
        return score;
    }

    private void checkIfTableReady()
    {
        synchronized(tableReady)
        {
            if(!tableReady.get())
            {
                isFree.set(false);
                if(wasShuffled.get())
                {
                    choices.clear();
                }
                wasShuffled.set(false);
            }
            while(!tableReady.get())
            {
                try
                {
                    tableReady.wait();
                }catch(InterruptedException ignored){}
            }
            isFree.set(true);
        }
    }

    private void waitForSet()
    {
        if(!human)
            runAI();
        synchronized(choices)
        {
            while((choices.size() < env.config.featureSize & !wasPenalized.get()) || (choices.size() == env.config.featureSize & wasPenalized.get()))
            {
                if(!tableReady.get())
                {
                    return;
                }
                try
                {
                    choices.wait();
                }catch(InterruptedException ignored){}
            }
            dealer.setSem.add(id);
            isFree.set(false);
            try
            {
                dealer.setSem.notifyAll();
            }catch(IllegalMonitorStateException ignored){}
        }
    }

    private void waitForRespond()
    {
        if((choices.size() < env.config.featureSize & !wasPenalized.get()) || (choices.size() == env.config.featureSize & wasPenalized.get()) & !tableReady.get())
            return;
        synchronized(wasChecked)
        {
            while(!wasChecked.get())
            {
                if((choices.size() < env.config.featureSize & !wasPenalized.get()) || (choices.size() == env.config.featureSize & wasPenalized.get()) & !tableReady.get())
                    return;
                try
                {
                    wasChecked.wait();
                }catch(InterruptedException ignored){}
            }
            if (wasShuffled.get())
                choices.clear(); 
        }
    }

    private void updatePenaltytime()
    {
        wasChecked.set(false);
        if (wasShuffled.get())
            choices.clear();
        //synchronized(choices)
        {
            if(wasPenalized.get())
            {
                for(Long i = env.config.penaltyFreezeMillis; i > 0; i = i - 1000)
                {
                    if(wasShuffled.get())
                        break;
                    env.ui.setFreeze(id, i);
                    try
                    {
                        Thread.sleep(1000);
                    }catch(InterruptedException ignored){}
                }
            }
            else
            {
                choices.clear();
                for(Long i = env.config.pointFreezeMillis; i > 0; i = i - 1000)
                {
                    if(wasShuffled.get())
                        break;
                    env.ui.setFreeze(id, i);
                    try
                    {
                        Thread.sleep(1000);
                    }catch(InterruptedException ignored){}
                }   
            }
            env.ui.setFreeze(id, -1);
            isFree.set(true);
            if(wasShuffled.get())
                wasPenalized.set(false);
        }
    }

    protected void runAI()
    {
        synchronized(aiRun)
        {
            while(aiRun.get())
            {
                try{
                    aiRun.wait();
                }catch(InterruptedException ignored){}
                catch(IllegalMonitorStateException ignored){}
            }
            aiRun.set(true);
            try{
                aiRun.notifyAll();
            }catch(IllegalMonitorStateException ignored){}
        }
    }

    public Boolean getWasPen()
    {
        return wasPenalized.get();
    }

    public boolean getTerminate()
    {
        return terminate;
    }

}
