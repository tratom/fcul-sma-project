package zanzibar.bazaar;

import jade.core.Agent;
import jade.core.behaviours.*;
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
 * Adapted to:
 *  - Dynamically track players joining/leaving each round
 *  - Introduce events from "Bazaar Specific Rules"
 *  - Adjust prices based on events and supply/demand
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
    private static final int MAX_ROUNDS = 5; // Adjust as needed

    // Track the current round’s event so that price changes or taxes can be applied
    private String currentEvent = "No special event";

    // Percentage (0 to 100) tax if the event is "Sultan’s Port Tax"
    private double sultansTaxRate = 0.0;

    // Store total sales per spice each round to do supply/demand logic
    private Map<String, Integer> roundSpiceSales;

    @Override
    protected void setup() {
        System.out.println(getLocalName() + " starting as Bazaar Agent...");

        // 1. Register in the Directory Facilitator (DF) as the bazaar-master
        registerService("bazaar-master");

        // 2. Initialize default prices
        currentPrices.put(CLOVE,    20);
        currentPrices.put(CINNAMON, 10);
        currentPrices.put(NUTMEG,   15);
        currentPrices.put(CARDAMOM,  5);

        // 3. Add the main behaviour to handle the entire game
        addBehaviour(new RoundManagerBehaviour(this));
    }

    /**
     * Helper method to register a service with the JADE DF.
     */
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

    // ------------------------------------------------
    //  Round Manager
    // ------------------------------------------------

    /**
     * Main behaviour that progresses the game through multiple rounds.
     * Uses a SequentialBehaviour that resets each round until reaching MAX_ROUNDS.
     */
    private class RoundManagerBehaviour extends SequentialBehaviour {

        public RoundManagerBehaviour(Agent a) {
            super(a);
        }

        @Override
        public void onStart() {
            System.out.println("\n=== Starting Round " + currentRound + " ===");

            // ** 0) Update the list of active players before sub-behaviours
            addSubBehaviour(new UpdatePlayerListBehaviour());

            // ** 1) Announce round begin
            addSubBehaviour(new AnnounceRoundStartBehaviour());
            
            // ** 2) Announce prices and events
            addSubBehaviour(new AnnouncePricesBehaviour());

            // ** 3) Request sales
            addSubBehaviour(new RequestSalesBehaviour());

            // ** 4) Collect sales
            addSubBehaviour(new CollectSalesBehaviour());

            // ** 5) End round (scoreboard + next round adjustments)
            addSubBehaviour(new EndRoundBehaviour());
        }

        @Override
        public int onEnd() {
            if (currentRound < MAX_ROUNDS) {
                currentRound++;
                reset(); // Reset the sub-behaviours for the next round
                myAgent.addBehaviour(new RoundManagerBehaviour(myAgent)); // Re-schedule itself
                return 0;
            } else {
                System.out.println("Reached MAX_ROUNDS. Terminating...");
                myAgent.doDelete();
                return super.onEnd();
            }
        }
    }

    // ------------------------------------------------
    //  Sub-Behaviours for each step
    // ------------------------------------------------
    
    /**
     * 0) Update the scoreboard with newly joined / departed players by DF lookup.
     */
    private class UpdatePlayerListBehaviour extends OneShotBehaviour {
        @Override
        public void action() {
            // Look up all "zanzibar-player" services
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType("zanzibar-player");
            template.addServices(sd);

            try {
                DFAgentDescription[] results = DFService.search(myAgent, template);

                // Build a set of current DF player names
                Set<String> dfPlayers = new HashSet<>();
                for (DFAgentDescription dfd : results) {
                    dfPlayers.add(dfd.getName().getLocalName());
                }

                // 1) Add new players that are not in scoreboard
                for (String dfPlayerName : dfPlayers) {
                    if (!scoreboard.containsKey(dfPlayerName) &&
                        !departedPlayers.containsKey(dfPlayerName)) {
                        scoreboard.put(dfPlayerName, 0);
                        System.out.println(">>> New player joined: " + dfPlayerName + " (score initialized to 0).");
                    }
                }

                // 2) Detect players that have left (in scoreboard but not in DF search)
                List<String> toRemove = new ArrayList<>();
                for (String playerName : scoreboard.keySet()) {
                    if (!dfPlayers.contains(playerName)) {
                        toRemove.add(playerName);
                    }
                }
                for (String leavingPlayer : toRemove) {
                    int finalScore = scoreboard.get(leavingPlayer);
                    scoreboard.remove(leavingPlayer);
                    departedPlayers.put(leavingPlayer, finalScore);
                    System.out.println(">>> Player " + leavingPlayer + " left. Final score saved: " + finalScore);
                }

            } catch (FIPAException fe) {
                fe.printStackTrace();
            }
        }
    }
    
    /**
     * 1) Announce the begin of a new round.
     * This way, all active players get a clear signal that negotiation for the new round is open.
     */
    private class AnnounceRoundStartBehaviour extends OneShotBehaviour {
        @Override
        public void action() {
            if (scoreboard.isEmpty()) {
                // If there are no active players, no need to broadcast
                System.out.println(getLocalName() + ": No active players for 'round-start'.");
                return;
            }

            ACLMessage startMsg = new ACLMessage(ACLMessage.INFORM);
            startMsg.setConversationId("round-start");
            // You can include any text you like to signal to players
            startMsg.setContent("Round " + currentRound + " has started. Prepare to negotiate!");

            for (String playerName : scoreboard.keySet()) {
                startMsg.addReceiver(getAID(playerName));
            }

            myAgent.send(startMsg);
            System.out.println(getLocalName() + ": Sent 'round-start' message to players.");
        }
    }

    /**
     * 2) Announce current prices and randomly chosen event to all active players.
     */
    private class AnnouncePricesBehaviour extends OneShotBehaviour {
        @Override
        public void action() {
            if (scoreboard.isEmpty()) {
                System.out.println(getLocalName() + ": No active players to announce to (scoreboard is empty).");
                return;
            }

            // Generate or choose an event for this round
            currentEvent = generateEvent();
            System.out.println(getLocalName() + ": Announcing prices for Round " + currentRound
                               + " with event: " + currentEvent);

            // Broadcast a message with the updated prices & event
            ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
            msg.setConversationId("price-announcement");
            msg.setContent(encodePriceEventInfo(currentPrices, currentEvent));

            for (String playerName : scoreboard.keySet()) {
                msg.addReceiver(getAID(playerName));
            }
            send(msg);

            System.out.println("> Sent price announcement: " + msg.getContent());
        }
    }

    /**
     * 3) Request that players submit how many units they will sell this round.
     */
    private class RequestSalesBehaviour extends OneShotBehaviour {
        @Override
        public void action() {
            if (scoreboard.isEmpty()) {
                System.out.println(getLocalName() + ": No active players to request sales from.");
                return;
            }

            System.out.println(getLocalName() + ": Requesting sales from players...");

            ACLMessage msg = new ACLMessage(ACLMessage.REQUEST);
            msg.setConversationId("sell-request");
            msg.setContent("How many units of each spice will you sell this round? (format: Spice=Qty;...)");

            for (String playerName : scoreboard.keySet()) {
                msg.addReceiver(getAID(playerName));
            }
            send(msg);
        }
    }

    /**
     * 4) Collect the sales. Track total sales to apply supply/demand logic.
     */
    private class CollectSalesBehaviour extends Behaviour {
        private int repliesReceived = 0;
        private boolean done = false;

        // Reset round sales to zero for each spice
        public CollectSalesBehaviour() {
            roundSpiceSales = new HashMap<>();
            roundSpiceSales.put(CLOVE,     0);
            roundSpiceSales.put(CINNAMON,  0);
            roundSpiceSales.put(NUTMEG,    0);
            roundSpiceSales.put(CARDAMOM,  0);
        }

        @Override
        public void action() {
            // Listen for "sell-response" messages
            MessageTemplate mt = MessageTemplate.MatchConversationId("sell-response");
            ACLMessage reply = myAgent.receive(mt);

            if (reply != null) {
                // Parse the player's response: e.g. "Clove=2;Cinnamon=0;Nutmeg=1;Cardamom=3"
                Map<String, Integer> soldMap = decodeSellInfo(reply.getContent());

                // Calculate coins gained (minus possible tax if event is Sultan’s Port Tax)
                String playerName = reply.getSender().getLocalName();
                int coinsGained = 0;

                for (Map.Entry<String, Integer> e : soldMap.entrySet()) {
                    String spice = e.getKey();
                    int quantity = e.getValue();
                    int price = currentPrices.getOrDefault(spice, 0);

                    coinsGained += price * quantity;

                    // Tally for supply/demand
                    roundSpiceSales.put(spice, roundSpiceSales.get(spice) + quantity);
                }

                // If we have a tax event, reduce coins gained by that percentage
                if (currentEvent.startsWith("Sultan's Port Tax")) {
                    int taxedAmount = (int) Math.round(coinsGained * sultansTaxRate);
                    coinsGained -= taxedAmount;
                    System.out.println("  [Tax Applied] " + playerName + " paid " + taxedAmount + " coins in taxes.");
                }

                // Update scoreboard
                int oldScore = scoreboard.getOrDefault(playerName, 0);
                scoreboard.put(playerName, oldScore + coinsGained);

                repliesReceived++;
                System.out.println(playerName + " sold " + soldMap
                        + " -> earned " + coinsGained + " coins (total " + scoreboard.get(playerName) + ").");
            } else {
                block(); // wait for next message
            }

            // If we've received all replies (assuming scoreboard size is # of active players)
            if (repliesReceived >= scoreboard.size()) {
                done = true;
            }
        }

        @Override
        public boolean done() {
            return done;
        }
    }

    /**
     * 5) EndRoundBehaviour:
     *    - Print scoreboard
     *    - Adjust prices according to event and supply/demand
     *    - Next round or end
     */
    private class EndRoundBehaviour extends OneShotBehaviour {
        @Override
        public void action() {
            System.out.println("\n--- End of Round " + currentRound + " ---");
            if (scoreboard.isEmpty()) {
                System.out.println("No active players this round.");
            } else {
                System.out.println("Current Scoreboard:");
                for (Map.Entry<String, Integer> entry : scoreboard.entrySet()) {
                    System.out.println("  " + entry.getKey() + " has " + entry.getValue() + " coins.");
                }
            }

            // Adjust next round's prices (apply event effects + supply/demand)
            adjustPricesForNextRound();

            // Reset any event-specific data, like sultansTaxRate
            sultansTaxRate = 0.0;
        }
    }

    // ------------------------------------------------
    //  Utility / Helper Methods
    // ------------------------------------------------

    /**
     *  Randomly pick one of the bazaar events:
     *   - Storm in the Indian Ocean => next round price up for some spice
     *   - Sultan's Port Tax => apply tax to all sales
     *   - New Trade Route => drastically reduce one spice's price
     *   - Or no event
     */
    private String generateEvent() {
        double r = Math.random();
        if (r < 0.25) {
            // Storm
            // We can randomly pick a spice to be “disrupted”
            String spiceAffected = pickRandomSpice();
            return "Storm in Indian Ocean: Next round " + spiceAffected + " price +5";
        } else if (r < 0.50) {
            // Sultan's tax
            // Let’s define a flat 10% tax for illustration
            sultansTaxRate = 0.10; 
            return "Sultan's Port Tax of 10% on sales this round";
        } else if (r < 0.75) {
            // New Trade Route discovered: pick a spice that drops drastically
            String spiceAffected = pickRandomSpice();
            return "New Trade Route Discovered: " + spiceAffected + " price drastically drops next round";
        } else {
            return "No special event";
        }
    }

    /**
     * Utility to pick a random spice name from known spices.
     */
    private String pickRandomSpice() {
        List<String> spices = new ArrayList<>(Arrays.asList(CLOVE, CINNAMON, NUTMEG, CARDAMOM));
        Collections.shuffle(spices);
        return spices.get(0);
    }

    /**
     * Encode market prices and event into a single string (e.g. "Clove=20;Cinnamon=10;...|EVENT:Storm...").
     * You could use JSON or other structured format in practice.
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
     * Decode a player's sell info of the form "Clove=2;Cinnamon=1;Nutmeg=0..."
     */
    private Map<String, Integer> decodeSellInfo(String content) {
        Map<String, Integer> soldMap = new HashMap<>();
        try {
            String[] parts = content.split(";");
            for (String p : parts) {
                String[] kv = p.split("=");
                if (kv.length == 2) {
                    soldMap.put(kv[0].trim(), Integer.parseInt(kv[1].trim()));
                }
            }
        } catch (Exception e) {
            System.err.println("Error parsing sell info: " + content);
        }
        return soldMap;
    }

    /**
     * Adjust prices for the next round, applying:
     *   - The event effect
     *   - A simple supply/demand rule based on how much was sold
     */
    private void adjustPricesForNextRound() {
        // 1) Check if the event from this round modifies next round's prices
        String spiceImpacted = null;
        boolean stormHappened = false;
        boolean routeHappened = false;

        if (currentEvent.startsWith("Storm in Indian Ocean")) {
            stormHappened = true;
            // e.g. "Storm in Indian Ocean: Next round Clove price +5"
            // parse out which spice got impacted
            // (the simplest approach: substring after "Next round")
            int idx = currentEvent.indexOf("Next round ");
            if (idx != -1) {
                String part = currentEvent.substring(idx + 11).trim(); // e.g. "Clove price +5"
                spiceImpacted = part.split(" ")[0];
            }
        }
        else if (currentEvent.startsWith("New Trade Route Discovered")) {
            routeHappened = true;
            // e.g. "New Trade Route Discovered: Nutmeg price drastically drops next round"
            int idx = currentEvent.indexOf(":");
            if (idx != -1) {
                String part = currentEvent.substring(idx + 1).trim(); // e.g. "Nutmeg price drastically drops next round"
                spiceImpacted = part.split(" ")[0];
            }
        }

        for (String spice : currentPrices.keySet()) {
            int oldPrice = currentPrices.get(spice);
            int newPrice = oldPrice;

            // 2) Apply supply/demand
            // If a lot of this spice was sold => supply is high => price goes down
            // If little was sold => supply is low => price goes up
            int totalSold = roundSpiceSales != null ? roundSpiceSales.get(spice) : 0;
            // Example thresholds: if totalSold > 10, price -2; if totalSold=0, price +2
            if (totalSold > 10) {
                newPrice -= 2; 
            } else if (totalSold == 0) {
                newPrice += 2;
            }

            // 3) Apply the event effect (Storm => +5, New Trade Route => large drop)
            if (stormHappened && spice.equals(spiceImpacted)) {
                newPrice += 5; 
            }
            if (routeHappened && spice.equals(spiceImpacted)) {
                // Drastic drop, e.g. -50% or minimum 1 coin
                newPrice = Math.max(1, newPrice / 2); 
            }

            // Random floor at 1
            if (newPrice < 1) {
                newPrice = 1;
            }

            currentPrices.put(spice, newPrice);
        }

        System.out.println("Adjusted prices for next round: " + currentPrices);
    }

    @Override
    protected void takeDown() {
        // Deregister from the DF
        try {
            DFService.deregister(this);
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }
        System.out.println(getLocalName() + " terminating.");
    }
}
