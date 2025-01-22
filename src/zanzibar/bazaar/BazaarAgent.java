package zanzibar.bazaar;

import jade.core.Agent;
import jade.core.behaviours.SequentialBehaviour;
import jade.core.behaviours.OneShotBehaviour;
import jade.core.behaviours.Behaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.*;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import java.util.*;

/**
 * BazaarAgent acts as the Game Master in the Zanzibar Bazaar scenario.
 * It announces market prices/events, collects sales from PlayerAgents,
 * updates the scoreboard, and manages rounds.
 *
 * Changes from previous version:
 *  - Storm, Sultan Tax, and New Trade Route amounts are randomly generated.
 *  - The event states these random values explicitly (so players see them).
 *  - The effect of Storm / New Trade Route is applied to prices in the *next* round.
 *  - Sultan's Port Tax is applied in the *current* round's sales, but the random
 *    percentage is announced at round start.
 */
public class BazaarAgent extends Agent {

    // Spice names
    public static final String CLOVE      = "Clove";
    public static final String CINNAMON   = "Cinnamon";
    public static final String NUTMEG     = "Nutmeg";
    public static final String CARDAMOM   = "Cardamom";

    // Current prices (coins per unit)
    private Map<String, Integer> currentPrices = new HashMap<>();

    // Track total coins per player (active scoreboard)
    private Map<String, Integer> scoreboard = new HashMap<>();

    // Keep a record of departed players for final stats
    private Map<String, Integer> departedPlayers = new HashMap<>();

    // Round management
    private int currentRound = 1;
    private int maxRounds = 5; // Overridden if arguments passed in

    // The event string for the current round
    // e.g. "Storm: Next round Nutmeg +7" or "Sultan's Port Tax of 15%..." or "New Trade Route: next round factor=0.4"
    private String currentEvent = "No special event";

    // Percentage tax if the event is "Sultan’s Port Tax" (applies this round only)
    private double sultansTaxRate = 0.0;

    // Store total sales per spice each round (for supply/demand logic)
    private Map<String, Integer> roundSpiceSales;

    @Override
    protected void setup() {
        System.out.println(getLocalName() + " starting as Bazaar Agent...");

        // 1) Check for a custom maxRounds argument
        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            try {
                maxRounds = Integer.parseInt(args[0].toString());
                System.out.println(getLocalName() + ": MAX_ROUNDS set to " + maxRounds
                                   + " from startup arguments.");
            } catch (NumberFormatException e) {
                System.out.println(getLocalName() + ": Invalid argument for maxRounds, using default of 5.");
            }
        }

        // 2) Register in the Directory Facilitator (DF)
        registerService("bazaar-master");

        // 3) Initialize default prices
        currentPrices.put(CLOVE,    20);
        currentPrices.put(CINNAMON, 10);
        currentPrices.put(NUTMEG,   15);
        currentPrices.put(CARDAMOM,  5);

        // 4) Add the main behaviour to handle all rounds
        addBehaviour(new RoundManagerBehaviour(this));
    }

    private void registerService(String serviceName) {
        try {
            DFAgentDescription dfd = new DFAgentDescription();
            dfd.setName(getAID());
            ServiceDescription sd = new ServiceDescription();
            sd.setType(serviceName);
            sd.setName(getLocalName() + "-" + serviceName);
            dfd.addServices(sd);
            DFService.register(this, dfd);
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }
    }

    // -----------------------------------------------------------------
    //  Main Round Management
    // -----------------------------------------------------------------
    private class RoundManagerBehaviour extends SequentialBehaviour {
        public RoundManagerBehaviour(Agent a) {
            super(a);
        }

        @Override
        public void onStart() {
            System.out.println("\n=== Starting Round " + currentRound + " ===");

            // Sub-behaviours in order:
            addSubBehaviour(new UpdatePlayerListBehaviour());
            addSubBehaviour(new AnnounceRoundStartBehaviour());
            addSubBehaviour(new AnnouncePricesBehaviour());
            addSubBehaviour(new RequestSalesBehaviour());
            addSubBehaviour(new CollectSalesBehaviour());
            addSubBehaviour(new EndRoundBehaviour());
        }

        @Override
        public int onEnd() {
            if (currentRound < maxRounds) {
                currentRound++;
                reset(); 
                myAgent.addBehaviour(new RoundManagerBehaviour(myAgent)); // schedule next round
                return 0;
            } else {
                System.out.println("Reached MAX_ROUNDS = " + maxRounds + ". Ending game...");
                sendGameOverToPlayers();
                myAgent.doDelete();
                return super.onEnd();
            }
        }
    }

    // -----------------------------------------------------------------
    //  Sub-Behaviours for each round step
    // -----------------------------------------------------------------
    /**
     * 0) Update scoreboard with new/left players from DF
     */
    private class UpdatePlayerListBehaviour extends OneShotBehaviour {
        @Override
        public void action() {
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType("zanzibar-player");
            template.addServices(sd);

            try {
                DFAgentDescription[] results = DFService.search(myAgent, template);

                // Build set of current DF player names
                Set<String> dfPlayers = new HashSet<>();
                for (DFAgentDescription dfd : results) {
                    dfPlayers.add(dfd.getName().getLocalName());
                }

                // Add new players not in scoreboard or departed
                for (String dfPlayerName : dfPlayers) {
                    if (!scoreboard.containsKey(dfPlayerName) &&
                        !departedPlayers.containsKey(dfPlayerName)) {
                        scoreboard.put(dfPlayerName, 0);
                        System.out.println(">>> New player joined: " + dfPlayerName + " (score=0).");
                    }
                }

                // Detect who left
                List<String> toRemove = new ArrayList<>();
                for (String playerName : scoreboard.keySet()) {
                    if (!dfPlayers.contains(playerName)) {
                        toRemove.add(playerName);
                    }
                }
                for (String leaving : toRemove) {
                    int finalScore = scoreboard.get(leaving);
                    scoreboard.remove(leaving);
                    departedPlayers.put(leaving, finalScore);
                    System.out.println(">>> Player " + leaving + " left. Final score: " + finalScore);
                }
            } catch (FIPAException fe) {
                fe.printStackTrace();
            }
        }
    }

    /**
     * 1) Announce new round
     */
    private class AnnounceRoundStartBehaviour extends OneShotBehaviour {
        @Override
        public void action() {
            if (scoreboard.isEmpty()) {
                System.out.println(getLocalName() + ": No active players for 'round-start'.");
                return;
            }

            ACLMessage startMsg = new ACLMessage(ACLMessage.INFORM);
            startMsg.setConversationId("round-start");
            startMsg.setContent("Round " + currentRound + " has started. Prepare to negotiate!");

            for (String playerName : scoreboard.keySet()) {
                startMsg.addReceiver(getAID(playerName));
            }
            send(startMsg);

            System.out.println(getLocalName() + ": Sent 'round-start' to players.");
        }
    }

    /**
     * 2) Announce prices + event (with random effect). 
     *    Storm / Sultan's Tax / New Route / or No event.
     */
    private class AnnouncePricesBehaviour extends OneShotBehaviour {
        @Override
        public void action() {
            if (scoreboard.isEmpty()) {
                System.out.println(getLocalName() + ": No players to announce prices.");
                return;
            }

            currentEvent = generateEvent();  
            System.out.println(getLocalName() + ": Announcing event for Round " + currentRound
                               + " => " + currentEvent);

            // Build message with current prices + event
            ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
            msg.setConversationId("price-announcement");
            msg.setContent(encodePriceEventInfo(currentPrices, currentEvent));

            for (String playerName : scoreboard.keySet()) {
                msg.addReceiver(getAID(playerName));
            }
            send(msg);

            System.out.println("> Sent price-announcement: " + msg.getContent());
        }
    }

    /**
     * 3) Request sales from each player
     */
    private class RequestSalesBehaviour extends OneShotBehaviour {
        @Override
        public void action() {
            if (scoreboard.isEmpty()) {
                System.out.println(getLocalName() + ": No active players to request sales from.");
                return;
            }
            System.out.println(getLocalName() + ": Requesting sales...");

            ACLMessage req = new ACLMessage(ACLMessage.REQUEST);
            req.setConversationId("sell-request");
            req.setContent("How many units of each spice will you sell this round? (Spice=Qty;...)");

            for (String playerName : scoreboard.keySet()) {
                req.addReceiver(getAID(playerName));
            }
            send(req);
        }
    }

    /**
     * 4) Collect sales
     */
    private class CollectSalesBehaviour extends Behaviour {
        private int replies = 0;
        private boolean doneFlag = false;

        // Reset round sales to zero
        public CollectSalesBehaviour() {
            roundSpiceSales = new HashMap<>();
            roundSpiceSales.put(CLOVE,     0);
            roundSpiceSales.put(CINNAMON,  0);
            roundSpiceSales.put(NUTMEG,    0);
            roundSpiceSales.put(CARDAMOM,  0);
        }

        @Override
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchConversationId("sell-response");
            ACLMessage reply = myAgent.receive(mt);
            if (reply != null) {
                String playerName = reply.getSender().getLocalName();
                Map<String, Integer> soldMap = decodeSellInfo(reply.getContent());

                // Calculate coins
                int coinsGained = 0;
                for (Map.Entry<String, Integer> e : soldMap.entrySet()) {
                    String spice = e.getKey();
                    int qty = e.getValue();
                    int price = currentPrices.getOrDefault(spice, 0);

                    coinsGained += price * qty;

                    // Tally for supply/demand
                    roundSpiceSales.put(spice, roundSpiceSales.get(spice) + qty);
                }

                // If we had a Sultan's Tax this round, apply it
                if (currentEvent.startsWith("Sultan's Port Tax")) {
                    // e.g. "Sultan's Port Tax of 12% on sales this round"
                    int tax = (int)Math.round(coinsGained * sultansTaxRate);
                    coinsGained -= tax;
                    System.out.println("  [Tax] " + playerName + " pays " + tax + " coins in taxes.");
                }

                // Update scoreboard
                int oldScore = scoreboard.getOrDefault(playerName, 0);
                scoreboard.put(playerName, oldScore + coinsGained);
                replies++;

                System.out.println(playerName + " sold " + soldMap + " => " + coinsGained
                        + " coins (total " + scoreboard.get(playerName) + ")");
            } else {
                block();
            }

            if (replies >= scoreboard.size()) {
                doneFlag = true;
            }
        }

        @Override
        public boolean done() {
            return doneFlag;
        }
    }

    /**
     * 5) End Round: show scoreboard, apply supply/demand + event effect for next round
     */
    private class EndRoundBehaviour extends OneShotBehaviour {
        @Override
        public void action() {
            System.out.println("\n--- End of Round " + currentRound + " ---");
            if (scoreboard.isEmpty()) {
                System.out.println("No players this round.");
            } else {
                System.out.println("Scoreboard:");
                for (Map.Entry<String, Integer> e : scoreboard.entrySet()) {
                    System.out.println("  " + e.getKey() + ": " + e.getValue());
                }
            }

            adjustPricesForNextRound();

            // Reset tax each round
            sultansTaxRate = 0.0;
        }
    }

    // -----------------------------------------------------------------
    //  Utility Methods
    // -----------------------------------------------------------------

    /**
     * Send 'game-over' to all active players so they can terminate themselves.
     */
    private void sendGameOverToPlayers() {
        if (scoreboard.isEmpty()) {
            return;
        }
        ACLMessage gameOverMsg = new ACLMessage(ACLMessage.INFORM);
        gameOverMsg.setConversationId("game-over");
        gameOverMsg.setContent("The bazaar game has ended. Goodbye!");
        for (String playerName : scoreboard.keySet()) {
            gameOverMsg.addReceiver(getAID(playerName));
        }
        send(gameOverMsg);
        System.out.println(getLocalName() + ": Sent 'game-over' to remaining players.");
    }

    /**
     * Generate a random event. The actual price/tax changes are
     * applied in the next round (except the tax is for this round's sales).
     */
    private String generateEvent() {
        double r = Math.random();
        Random rng = new Random();
        if (r < 0.33) {
            // Storm
            // Pick spice
            String spice = pickRandomSpice();
            // Random +X from e.g. 2..8
            int stormAmount = 2 + rng.nextInt(7); // [2..8]
            return "Storm in Indian Ocean: Next round " + spice + " price +" + stormAmount;
        }
        else if (r < 0.66) {
            // Sultan's Port Tax
            // random percent 5..20
            int taxPercent = 5 + rng.nextInt(16); // [5..20]
            sultansTaxRate = taxPercent / 100.0;  // e.g. 0.12
            return "Sultan's Port Tax of " + taxPercent + "% on sales this round";
        }
        else {
            // New Trade Route
            String spice = pickRandomSpice();
            // random factor 0.3..0.7
            double factor = 0.3 + (0.4 * rng.nextDouble()); // [0.3..0.7]
            // Round to two decimals
            double f2 = Math.round(factor * 100.0) / 100.0;
            return "New Trade Route Discovered: " + spice + " next round price x" + f2;
        }
    }

    private String pickRandomSpice() {
        List<String> spices = Arrays.asList(CLOVE, CINNAMON, NUTMEG, CARDAMOM);
        Collections.shuffle(spices);
        return spices.get(0);
    }

    /**
     * After the round ends, update prices for next round based on:
     *  - Supply/demand
     *  - Storm or new trade route effect from the event text
     */
    private void adjustPricesForNextRound() {
        // parse if there's a Storm or a New Trade Route
        // We'll look for the strings we used:
        // Storm: "Storm in Indian Ocean: Next round <spice> price +X"
        // Route: "New Trade Route Discovered: <spice> next round price xFactor"
        
        // 1) supply/demand
        for (String spice : currentPrices.keySet()) {
            int oldPrice = currentPrices.get(spice);
            int newPrice = oldPrice;

            int soldQty = (roundSpiceSales != null) ? roundSpiceSales.get(spice) : 0;
            // if a lot sold => price down a bit
            if (soldQty > 10) {
                newPrice -= 2;
            }
            // if none sold => price up a bit
            else if (soldQty == 0) {
                newPrice += 2;
            }

            // keep 1 as minimum
            if (newPrice < 1) newPrice = 1;
            currentPrices.put(spice, newPrice);
        }

        // 2) event effect
        // STORM
        if (currentEvent.startsWith("Storm in Indian Ocean:")) {
            // e.g. "Storm in Indian Ocean: Next round Nutmeg price +7"
            // parse out the spice and the +X
            try {
                // find "Next round "
                int idx = currentEvent.indexOf("Next round ");
                if (idx != -1) {
                    String part = currentEvent.substring(idx + 11).trim(); // e.g. "Nutmeg price +7"
                    String[] tokens = part.split(" "); 
                    // tokens[0] = "Nutmeg"
                    // tokens[1] = "price"
                    // tokens[2] = "+7"
                    String spiceImpacted = tokens[0];
                    int plusAmount = 0;
                    if (tokens.length >= 3 && tokens[2].startsWith("+")) {
                        plusAmount = Integer.parseInt(tokens[2].replace("+", ""));
                    }
                    // apply
                    int oldPrice = currentPrices.getOrDefault(spiceImpacted, 1);
                    int newPrice = oldPrice + plusAmount;
                    if (newPrice < 1) newPrice = 1;
                    currentPrices.put(spiceImpacted, newPrice);
                }
            } catch (Exception ex) {
                System.err.println("Error parsing storm event: " + ex);
            }
        }
        // NEW TRADE ROUTE
        else if (currentEvent.startsWith("New Trade Route Discovered:")) {
            // e.g. "New Trade Route Discovered: Clove next round price x0.45"
            try {
                int idx = currentEvent.indexOf(":");
                if (idx != -1) {
                    // "Clove next round price x0.45"
                    String part = currentEvent.substring(idx + 1).trim();
                    // split => "Clove", "next", "round", "price", "x0.45"
                    String[] tokens = part.split(" ");
                    if (tokens.length >= 5) {
                        String spiceImpacted = tokens[0];
                        String factorToken = tokens[4]; // "x0.45"
                        double factor = 1.0;
                        if (factorToken.startsWith("x")) {
                            factor = Double.parseDouble(factorToken.replace("x", ""));
                        }
                        // apply
                        int oldPrice = currentPrices.getOrDefault(spiceImpacted, 1);
                        // multiply
                        double newP = oldPrice * factor;
                        int finalPrice = (int)Math.round(newP);
                        if (finalPrice < 1) finalPrice = 1;
                        currentPrices.put(spiceImpacted, finalPrice);
                    }
                }
            } catch (Exception ex) {
                System.err.println("Error parsing new trade route event: " + ex);
            }
        }

        System.out.println("Prices after Round " + currentRound + " adjustments: " + currentPrices);
    }

    /**
     * Encode prices + event in a single string
     */
    private String encodePriceEventInfo(Map<String, Integer> prices, String eventMsg) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : prices.entrySet()) {
            sb.append(e.getKey()).append("=").append(e.getValue()).append(";");
        }
        sb.append("|EVENT:").append(eventMsg);
        return sb.toString();
    }

    /**
     * Decode player's sell info: "Clove=2;Cinnamon=0;..."
     */
    private Map<String, Integer> decodeSellInfo(String content) {
        Map<String, Integer> sold = new HashMap<>();
        try {
            String[] parts = content.split(";");
            for (String p : parts) {
                String[] kv = p.split("=");
                if (kv.length == 2) {
                    sold.put(kv[0].trim(), Integer.parseInt(kv[1].trim()));
                }
            }
        } catch (Exception e) {
            System.err.println("Error parsing sell info: " + content);
        }
        return sold;
    }

    @Override
    protected void takeDown() {
        // Deregister
        try {
            DFService.deregister(this);
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }
        System.out.println(getLocalName() + " terminating.");
    }
}
