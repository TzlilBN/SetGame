package bguspl.set.ex;

import bguspl.set.Config;
import bguspl.set.Env;
import bguspl.set.UserInterface;
import bguspl.set.Util;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Properties;

@ExtendWith(MockitoExtension.class)
class DealerTest {

    Dealer dealer;
    @Mock
    Util util;
    @Mock
    private UserInterface ui;
    @Mock
    private Table table;
    @Mock
    private Player player;
    @Mock
    private Logger logger;

    Table tableMock;
    Player player1;
    private Integer[] slotToCard;
    private Integer[] cardToSlot;
    
    @BeforeEach
    void setUp() {
        Properties properties = new Properties();
        properties.put("Rows", "2");
        properties.put("Columns", "2");
        properties.put("FeatureSize", "3");
        properties.put("FeatureCount", "4");
        properties.put("TableDelaySeconds", "0");
        properties.put("PlayerKeys1", "81,87,69,82");
        properties.put("PlayerKeys2", "85,73,79,80");
        // purposely do not find the configuration files (use defaults here).
        Env env = new Env(logger, new Config(logger, properties), ui, util);
        Player[] players = new Player[2];
        slotToCard = new Integer[env.config.tableSize];
        cardToSlot = new Integer[env.config.deckSize];
        tableMock = new Table(env, slotToCard, cardToSlot);
        player1 = new Player(env, dealer, tableMock, 0, true);
        players[0] = player1; 
        dealer = new Dealer(env, tableMock, players);
    }

    private void fillAllSlots() {
        for (int i = 0; i < tableMock.slotToCard.length; ++i) {
            tableMock.slotToCard[i] = i;
            tableMock.cardToSlot[i] = i;
        }
    }

    private void InsertSlotToPlayer1()
    {
        fillAllSlots();
        for(int i = 0; i<3; i++)
            player1.keyPressed(i);
    }


    @Test
    void makeSetfromPLayer_checkIfSemSetEmpty()
    {
        fillAllSlots();
        // calculate the expected wasPenalized for later
        int[] expectedSet = null;

        // call the method we are testing
        int[] ans = dealer.makeSetfromPLayer();

        // check that wasPenalized was changed correctly
        assertEquals(expectedSet, ans);
    }

    @Test
    void makeSetfromPLayer_checkTheSet() {

        InsertSlotToPlayer1();

        dealer.setSem.add(player1.id);
         // calculate the expected terminate value for later
        int[] expectedSet = new int[3];
        for (int i = 0; i < 3; i++)
            expectedSet[i] = tableMock.cardToSlot[i];

        int[] output = dealer.makeSetfromPLayer();

         // check that the score was increased correctly
         assertArrayEquals(expectedSet, output);
    }
 
}