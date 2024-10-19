package bguspl.set.ex;

import bguspl.set.Env;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.Collections;
import java.util.LinkedList;
import java.util.concurrent.*;

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
    private List<Thread> playerThreads;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    protected final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    /*
     * the Queue of players that need their set to get checked
     */
    protected LinkedBlockingIntegerQueueSemaphore setSem;

    protected long prevT;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        setSem = new LinkedBlockingIntegerQueueSemaphore(new ConcurrentLinkedQueue<Integer>());
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("Thread " + Thread.currentThread().getName() + " starting.");
        placeCardsOnTable();
        startGame();
        while (!terminate)
        {
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
            timerLoop();
            endTimerProtocol();
            removeAllCardsFromTable();
            if(terminate)
                break;
            placeCardsOnTable();
            BokerTovAyara();
        }
        terminate();
        terminateThreads();
        announceWinners();
        env.logger.info("Thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        while (!terminate && System.currentTimeMillis() < reshuffleTime)
        {
            long startTloop = System.currentTimeMillis();
            prevT = System.currentTimeMillis() - 1000;
            updateTimerDisplayLoop(true ,false, startTloop);
            Boolean foundSet = false;
            while(!foundSet)
            {
                foundSet = sleepUntilWokenOrSecond(startTloop);
                if(foundSet == null || foundSet)
                    break;
                if(!foundSet & setSem.Sets.peek()!= null)
                    foundBadSetProtocol();
            }
            if(foundSet == null)
                    continue;
            if (foundSet)
            {
                foundGoodSetProtocol();
                reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
                continue;
            }
        }
    }

    private void startGame()
    {
        playerThreads = new LinkedList<Thread>();
        for (Player player: players)
            playerThreads.add(new Thread(player));
        for (Thread t: playerThreads)
               t.start();
    }


    /**
     * Called when the game should be terminated.
     */
    public void terminate()
    {
        for(Player player: players)
        {
            synchronized(player.tableReady)
            {
                player.terminate();
                player.tableReady.set(true);
                try
                {
                    player.tableReady.notifyAll();
                }catch(IllegalMonitorStateException ignored){}
            }
        }
    }

    public void terminateThreads()
    {
        for(int i = players.length-1; i >= 0; i--)
        {
            synchronized(players[i].isFree)
            {
                players[i].isFree.set(false);
                try
                {
                    players[i].isFree.notifyAll();
                }catch(IllegalMonitorStateException ignored){}
            }
        }
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardFromTableAbdDeck(int slot) 
    {
        Integer CardtoRemove = table.slotToCard[slot];
        deck.remove(CardtoRemove);
        table.removeCard(slot);
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() 
    {
        Collections.shuffle(deck);
        for (int i = 0; i < env.config.rows*env.config.columns; i++)
        {
            if(table.slotToCard[i] == null & !deck.isEmpty())
            {
                table.placeCard(deck.get(0), i);
                deck.remove(0);
            }
        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private Boolean sleepUntilWokenOrSecond(long startTloop) {
        synchronized(setSem)
        {
            while(this.setSem.Sets.isEmpty()/*  & startTloop + env.config.turnTimeoutMillis > System.currentTimeMillis()*/)
            {
                if(startTloop + env.config.turnTimeoutMillis < System.currentTimeMillis())
                    return null;
                updateTimerDisplayLoop(System.currentTimeMillis() - prevT >= 1000 ,env.config.turnTimeoutMillis - (System.currentTimeMillis()-startTloop)<= env.config.turnTimeoutWarningMillis, startTloop);
                try{
                    setSem.wait(Math.min(1000, System.currentTimeMillis() - prevT));
                }catch(InterruptedException ignored){}
                 catch(IllegalMonitorStateException ignored){}       
            }
            updateTimerDisplayLoop(System.currentTimeMillis() - prevT >= 1000 ,env.config.turnTimeoutMillis - (System.currentTimeMillis()-startTloop)<= env.config.turnTimeoutWarningMillis, startTloop);
            if(startTloop + env.config.turnTimeoutMillis < System.currentTimeMillis())
                return null;
            return checkIfSetGood(makeSetfromPLayer());
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplayLoop(boolean reset ,boolean warning, long startTloop) {
        if (reset)
        {
            if(warning)
            {
                env.ui.setCountdown((((env.config.turnTimeoutMillis - (System.currentTimeMillis() - startTloop))/1000)*1000), warning);
                prevT = System.currentTimeMillis() - 1000;
            }
            else
            {
            env.ui.setCountdown(env.config.turnTimeoutMillis - (System.currentTimeMillis() - startTloop), warning);
            prevT = System.currentTimeMillis() - 1000;
            }
        }
    }

    public int[] makeSetfromPLayer()
     {
        if (setSem.Sets.peek() == null)
            return null;
        int player = setSem.Sets.peek();
        int[] Set = new int[env.config.featureSize];
        int j = 0;
        for (int i: players[player].choices)
        {
            Set[j] = table.slotToCard[i];
            j++;
        }
        return Set;
    }

    private boolean checkIfSetGood (int[] Set)
     { 
        if (Set == null)
            return false;
        if (env.util.testSet(Set))
            return true;
        else
            return false;
     }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {

        //Backend
        env.ui.removeTokens();
        for (Player player: players)
        {
            synchronized(player.choices)
            {
                player.choices.clear();
            }
        }
        for (int i = 0; i <env.config.rows*env.config.columns; i++)
        {
            if(table.slotToCard[i] != null)
            {
                deck.add(table.slotToCard[i]);
                table.removeCard(i);
            }
        }
        terminate = shouldFinish();
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        int winnerScore = 0; 
        List<Integer> winners = new LinkedList<Integer>();
        for (Player player: players)
        {
            if (player.score() > winnerScore)
            {
                winners = new LinkedList<Integer>();
                winners.add(player.id);
                winnerScore = player.score();
            }
            else
                if (player.score() == winnerScore)
                    winners.add(player.id);
        }
        int[] arrWinnners = new int[winners.size()];
        int i = 0;
        for(int winner: winners)
        {
            arrWinnners[i] = winner;
            i++;
        }
        env.ui.announceWinner(arrWinnners);
    }


    private void foundBadSetProtocol()
    {
        Player player = players[setSem.Sets.remove()];
        synchronized(player.wasChecked)
        {
            player.wasChecked.set(true);
            player.penalty();
            try
            {
            player.wasChecked.notifyAll();
            }catch(IllegalMonitorStateException ignored){}
        }
    }

    private void foundGoodSetProtocol()
    {
        Player winner = players[setSem.Sets.remove()];
        for(Player p: players)
            p.tableReady.set(false);
        for(int i: winner.choices)
            removeCardFromTableAbdDeck(i);
        for(Player p: players)
            for(int i: winner.choices)
                if(p.id != winner.id)
                    p.choices.remove(i);
        for(int player: setSem.Sets)
        {
            if(players[player].choices.size()<env.config.featureSize)
            {
                setSem.Sets.remove(player);
                synchronized(players[player].wasChecked)
                {
                    try
                    {
                        players[player].wasChecked.notifyAll();
                    }catch(IllegalMonitorStateException ignored){}
                }
            }
        }
        placeCardsOnTable();
        winner.point();
        synchronized(winner.wasChecked)
        {
            winner.wasChecked.set(true);
            try
            {
                winner.wasChecked.notifyAll();
            }catch(IllegalMonitorStateException ignored){}
        }
        for(Player p: players)
        {
            if(!p.wasShuffled.get())
                synchronized(p.tableReady)
                {
                    p.tableReady.set(true);
                    try
                    {
                    p.tableReady.notifyAll();
                    }catch(IllegalMonitorStateException ignored){}
                }
        }
    }

    private void endTimerProtocol()
    {
        for(Player p: players)
        {
            p.wasChecked.set(false);
            p.isFree.set(false);
            p.tableReady.set(false);
            p.wasShuffled.set(true);
            p.wasPenalized.set(false);
            p.choices.clear();
        }
        for(Player player:players)
        {
            if(!setSem.Sets.contains(player.id))
                synchronized(player.choices)
                {
                    player.choices.notifyAll();
                }
        }
        while (!setSem.Sets.isEmpty())
        {
            int player = setSem.Sets.remove();
            synchronized(players[player].wasChecked)
            {
                try
                {
                    players[player].wasChecked.notifyAll();
                }catch(IllegalMonitorStateException ignored){}
            }
        }
    }

    private void BokerTovAyara()
    {
        for(Player player: players)
        {
            synchronized(player.tableReady)
            {
                player.tableReady.set(true);
                try
                {
                    player.tableReady.notifyAll();
                }catch(IllegalMonitorStateException ignored){}
            }
        }
    }
}

class LinkedBlockingIntegerQueueSemaphore extends LinkedBlockingQueue<Integer>
{
    protected Semaphore sem;
    
    protected ConcurrentLinkedQueue<Integer> Sets;

    public LinkedBlockingIntegerQueueSemaphore(ConcurrentLinkedQueue<Integer> Sets)
    {
        this.sem = new Semaphore(1, true);
        this.Sets = Sets;
    }

    public void add(int i)
    {
        try{
            sem.acquire();
        }catch(InterruptedException ignored){}
        Sets.add(i);
        sem.release();
    }
}