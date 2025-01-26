package zanzibar.bazaar;

import jade.core.Agent;
import jade.core.AID;
import jade.core.behaviours.*;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;

import java.util.*;

/**
 * A PlayerAgent that:
 *  - Registers as "zanzibar-player" in the DF.
 *  - Uses a BDI-style model:
 *     Beliefs:  Historical prices, current prices, events, alliances/rivals
 *     Desires:  Maximize profit, capitalize on price increases, mitigate losses
 *     Intentions:  Propose trades, accept/reject offers, sell (or hold) inventory
 *
 *  - Waits for "round-start" from the GM, sets negotiationOpen = true.
 *  - Has a NegotiationInitiatorBehavior that checks negotiationOpen each tick
 *    and attempts trades up to a fallback, but only to different agents (max 3).
 *  - Has a AllianceProposerBehavior that propose alliances each tick.
 *  - Responds to trade/alliance proposals from other players.
 *  - When the GM requests a sell decision, the agent replies only after
 *    at least one negotiation has succeeded or the fallback is triggered.
 */
public class AdvancedPlayerAgent extends Agent {

    // ------------------------------------------------
    // BDI: Beliefs
    // ------------------------------------------------
    /** 
     * Stores the agent’s knowledge of current round prices. 
     * Key = Spice name, Value = current price 
     */
    private Map<String, Integer> currentPrices = new HashMap<>();

    /** 
     * Historical prices for each spice, used for naive forecasting.
     * Key = Spice name, Value = list of prices over previous rounds (oldest first).
     */
    private Map<String, List<Integer>> historicalPrices = new HashMap<>();
    private String previousEvent = null;
    
    /** 
     * Stores the agent’s forecast of next round prices. 
     * Key = Spice name, Value = predicted price 
     */
    private Map<String, Integer> predictedPrices = new HashMap<>();

    /**
     * Current event description (e.g., "Storm in Indian Ocean", "Sultan's Tax of 10%", etc.).
     */
    private String currentEvent = "No event";

    /** 
     * Agent’s local inventory: how many units of each spice the agent holds. 
     * Key = Spice name, Value = quantity 
     */
    private Map<String, Integer> inventory = new HashMap<>();

    /** Current coins on hand. (Not heavily used in this example.) */
    private int coins = 0;

    /** Allies are given more favorable trades; rivals, more demanding ones. */
    private Set<AID> allies = new HashSet<>();
    private Set<AID> rivals = new HashSet<>();

    /** 
     * List of known players in the DF (besides self).
     */
    private Set<AID> otherPlayers = new HashSet<>();

    // ------------------------------------------------
    // BDI: Desires (conceptual)
    // ------------------------------------------------
    // (See code logic for how these desires shape trade/sell decisions.)

    // ------------------------------------------------
    //  Intentions & Execution Control
    // ------------------------------------------------

    /**
     * negotiationOpen: Are we allowed to negotiate trades right now?
     */
    private boolean negotiationOpen = false;

    /**
     * Maximum number of attempts to initiate a trade per round.
     * We do not send multiple proposals to the same agent in the same round.
     */
    private static final int MAX_NEGOTIATION_ATTEMPTS = 3;
    private int negotiationAttempts = 0;

    /** How often we spontaneously propose trades (ms). */
    private static final long NEGOTIATION_INTERVAL = 2000;

    /** How often we spontaneously propose alliances (ms). */
    private static final long ALLIANCE_INTERVAL = 10000;

    /** Price difference between current price and prediction to make the sell decision. */
    private static final int SELL_DECISION_THRESHOLD = 3;

    /**
     * If the GM requests a "sell decision," we store it here until we are
     * done negotiating or hit fallback.
     */
    private ACLMessage pendingSellRequest = null;

    /**
     * Keep track of which agents we have **already proposed to** in the current round.
     * We will not re-propose to them if they have accepted or rejected once.
     */
    private Set<AID> alreadyProposedThisRound = new HashSet<>();

    // ------------------------------------------------
    // Setup / Initialization
    // ------------------------------------------------
    @Override
    protected void setup() {
        System.out.println(getLocalName() + " initializing...");

        // Introduce a delay before starting (for debugging / sniffers).
        final int START_DELAY = 30000; // 30 seconds in milliseconds
        try {
            System.out.println(getLocalName() + ": Delaying start by 30 seconds...");
            Thread.sleep(START_DELAY);
        } catch (InterruptedException e) {
            System.out.println(getLocalName() + ": Interrupted during delay. Starting immediately...");
        }
        
        System.out.println(getLocalName() + " is starting. Registering as zanzibar-player...");

        // 1) Register as player
        registerAsPlayer("zanzibar-player");

        // 2) Initialize random inventory
        Random rand = new Random();
        inventory.put(BazaarAgent.CLOVE,    1 + rand.nextInt(5));  
        inventory.put(BazaarAgent.CINNAMON, 1 + rand.nextInt(30));
        inventory.put(BazaarAgent.NUTMEG,   1 + rand.nextInt(10));
        inventory.put(BazaarAgent.CARDAMOM, 1 + rand.nextInt(25));

        // 3) Initialize historicalPrices structure so we don’t get NPE.
        for (String spice : Arrays.asList(BazaarAgent.CLOVE, BazaarAgent.CINNAMON,
                                          BazaarAgent.NUTMEG, BazaarAgent.CARDAMOM)) {
            historicalPrices.put(spice, new ArrayList<>());
        }
        
        // 4) Add Behaviors:
        addBehaviour(new MarketMessageListener());    // GM messages
        addBehaviour(new NegotiationResponder());     // handle trade offers
        addBehaviour(new AllianceResponder());        // handle alliance proposals
        addBehaviour(new SabotageBehavior(this, 15000)); // optional sabotage
        addBehaviour(new NegotiationInitiatorBehavior(this, NEGOTIATION_INTERVAL));
        addBehaviour(new AllianceProposerBehavior(this, ALLIANCE_INTERVAL));

        System.out.println(getLocalName() + " setup complete:");
        System.out.println(getLocalName() + " - initial inventory: " + inventory);
    }

    @Override
    protected void takeDown() {
        // Deregister from the DF
        try {
            DFService.deregister(this);
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }
        System.out.println(getLocalName() + " is terminating.");
    }

    // ---------------------------------------------------------------------
    //  DF Registration & Discovery
    // ---------------------------------------------------------------------
    private void registerAsPlayer(String serviceType) {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType(serviceType);
        sd.setName(getLocalName() + "-" + serviceType);
        dfd.addServices(sd);

        try {
            DFService.register(this, dfd);
        } catch (FIPAException e) {
            e.printStackTrace();
        }
    }

    /** Re-discover other zanzibar-player agents in the DF. */
    private void discoverOtherPlayers() {
        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType("zanzibar-player");
        template.addServices(sd);

        try {
            DFAgentDescription[] results = DFService.search(this, template);
            otherPlayers.clear();
            for (DFAgentDescription dfad : results) {
                AID provider = dfad.getName();
                if (!provider.equals(getAID())) {
                    otherPlayers.add(provider);
                }
            }
            System.out.println(getLocalName() + " discovered " + otherPlayers.size() + " other player(s).");
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }
    }

    // ---------------------------------------------------------------------
    //  BDI: Updating Beliefs from the Market (Observing the Market)
    // ---------------------------------------------------------------------
    private void updateHistoricalPrices() {
        // Store to historical record
        for (Map.Entry<String, Integer> entry : currentPrices.entrySet()) {
            String spice = entry.getKey();
            int price = entry.getValue();
            historicalPrices.get(spice).add(price);
        }
    }

    private void predictNextRoundPrices() {
        this.predictedPrices.clear();
        String spiceImpacted = null; 
                
        // 1) Start with current price as baseline
        for (Map.Entry<String, Integer> entry : currentPrices.entrySet()) {
            predictedPrices.put(entry.getKey(), entry.getValue());
        }

        // 2) Handle specific event patterns: Storm or New Trade Route
        if (currentEvent.contains("Storm in Indian Ocean")) {
            try {
                String[] parts = currentEvent.split("Next round")[1].trim().split(" ");
                spiceImpacted = parts[0];
                String plusStr = parts[2]; // e.g. "+5"
                int plusAmount = Integer.parseInt(plusStr.replace("+", ""));
                int oldPrice = predictedPrices.getOrDefault(spiceImpacted, 1);
                predictedPrices.put(spiceImpacted, Math.max(1, oldPrice + plusAmount));
            } catch (Exception e) {
                System.err.println(getLocalName() + ": Error parsing storm event => " + e);
            }
        } else if (currentEvent.contains("New Trade Route Discovered")) {
            try {
                String[] parts = currentEvent.split(":")[1].trim().split(" ");
                spiceImpacted = parts[0];
                String factorStr = parts[4]; // "x0.45"
                double factor = Double.parseDouble(factorStr.replace("x", ""));
                int oldPrice = predictedPrices.getOrDefault(spiceImpacted, 1);
                int newPrice = (int) Math.round(oldPrice * factor);
                predictedPrices.put(spiceImpacted, Math.max(1, newPrice));
            } catch (Exception e) {
                System.err.println(getLocalName() + ": Error parsing new trade route => " + e);
            }
        }

        // 3) For other spices, do a naive linear extrapolation from the last two data points
        for (String spice : predictedPrices.keySet()) {
            if (spice.equals(spiceImpacted)) {
                continue;
            }
            List<Integer> hist = historicalPrices.get(spice);
            if (hist.size() >= 2) {
                int last = hist.get(hist.size() - 1);
                int prev = hist.get(hist.size() - 2);
                int naiveNext = last + (last - prev) / 2;  // mild extrapolation
                predictedPrices.put(spice, Math.max(1, naiveNext));
            }
        }
    }

    // ---------------------------------------------------------------------
    //  GM Message Listener Behavior (Cyclic)
    // ---------------------------------------------------------------------
    private class MarketMessageListener extends CyclicBehaviour {
        @Override
        public void action() {
            ACLMessage msg = myAgent.receive();
            if (msg != null) {
                String convoId = msg.getConversationId();
                switch (convoId) {
                    case "round-start":
                        System.out.println(getLocalName()
                                + " received 'round-start'. Re-discovering players and enabling negotiation...");
                        discoverOtherPlayers();

                        // Reset negotiation attempts and set for new round
                        negotiationAttempts = 0;
                        alreadyProposedThisRound.clear();  // allow new proposals this round
                        break;

                    case "price-announcement":
                        handlePriceAnnouncement(msg);
                        negotiationOpen = true;  // negotiation can start
                        break;

                    case "sell-request":
                        handleSellRequest(msg);
                        break;

                    case "game-over":
                        handleGameOver(msg);
                        break;

                    default:
                        block();
                }
            } else {
                block();
            }
        }
    }

    // ---------------------------------------------------------------------
    //  Negotiation / Trade Initiation Behavior (Ticker)
    // ---------------------------------------------------------------------
    private class NegotiationInitiatorBehavior extends TickerBehaviour {
        public NegotiationInitiatorBehavior(Agent a, long period) {
            super(a, period);
        }

        @Override
        protected void onTick() {
            // If negotiation is not open, skip
            if (!negotiationOpen) {
                return;
            }

            // If we've reached the max attempts, fallback
            if (negotiationAttempts >= MAX_NEGOTIATION_ATTEMPTS) {
                System.out.println(getLocalName()
                        + " reached max negotiation attempts without success. "
                        + "Fallback: ending negotiation phase and forcing sell if pending.");
                negotiationOpen = false;
                if (pendingSellRequest != null) {
                    sendSellResponse();
                }
                return;
            }

            // If we have fewer than 3 other players, fallback immediately 
            // (as per the requirement: "If there are not other 3 agent, run the fallback solution.")
            if (otherPlayers.size() < 3) {
                System.out.println(getLocalName() 
                        + ": Fewer than 3 other players available => fallback / end negotiation now.");
                negotiationOpen = false;
                if (pendingSellRequest != null) {
                    sendSellResponse();
                }
                return;
            }

            // Build a list of potential partners we have NOT proposed to this round
            List<AID> uncontactedPartners = new ArrayList<>();
            for (AID p : otherPlayers) {
                if (!alreadyProposedThisRound.contains(p)) {
                    uncontactedPartners.add(p);
                }
            }

            // If no uncontacted partners remain, fallback
            if (uncontactedPartners.isEmpty()) {
                System.out.println(getLocalName() 
                        + ": No uncontacted partners left => fallback / end negotiation.");
                negotiationOpen = false;
                if (pendingSellRequest != null) {
                    sendSellResponse();
                }
                return;
            }

            // Otherwise, pick a random uncontacted partner
            AID partner = uncontactedPartners.get(new Random().nextInt(uncontactedPartners.size()));

            // Construct a proposal
            Map<String, Integer> offered = new HashMap<>();
            Map<String, Integer> requested = new HashMap<>();
            decideWhatToOfferAndRequest(offered, requested, partner);

            if (!offered.isEmpty() && !requested.isEmpty()) {
                String content = encodeTradeProposal(offered, requested);

                ACLMessage proposeMsg = new ACLMessage(ACLMessage.PROPOSE);
                proposeMsg.setConversationId("trade-offer");
                proposeMsg.addReceiver(partner);
                proposeMsg.setContent(content);
                send(proposeMsg);

                System.out.println(getLocalName() + " -> " + partner.getLocalName()
                        + ": PROPOSE " + content);

                // Mark that we've proposed to this partner this round
                alreadyProposedThisRound.add(partner);

                // Count as an attempt
                negotiationAttempts++;
            } else {
                System.out.println(getLocalName() + ": No valid trade offer to propose at this time.");
            }
        }
    }

    // ---------------------------------------------------------------------
    //  Negotiation: Responder to trade offers (Cyclic)
    // ---------------------------------------------------------------------
    private class NegotiationResponder extends CyclicBehaviour {
        @Override
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchConversationId("trade-offer");
            ACLMessage msg = myAgent.receive(mt);
            if (msg != null) {
                if (msg.getPerformative() == ACLMessage.PROPOSE) {
                    ACLMessage reply = msg.createReply();
                    Map<String, Integer> offered = new HashMap<>();
                    Map<String, Integer> requested = new HashMap<>();

                    decodeTradeProposal(msg.getContent(), offered, requested);

                    // Check if the trade is acceptable to us
                    if (isTradeAcceptable(offered, requested, msg.getSender())) {
                        // Accept
                        reply.setPerformative(ACLMessage.ACCEPT_PROPOSAL);
                        reply.setContent("Trade accepted!");
                        executeTrade(offered, requested);

                        System.out.println(getLocalName() + " accepted trade with "
                                + msg.getSender().getLocalName() + ": " + msg.getContent());
                        onNegotiationSuccess();
                    } else {
                        // Reject
                        reply.setPerformative(ACLMessage.REJECT_PROPOSAL);
                        reply.setContent("Trade rejected: not beneficial or not possible.");
                        System.out.println(getLocalName() + " rejected trade from "
                                + msg.getSender().getLocalName() + ": " + msg.getContent());
                    }
                    send(reply);
                }
            } else {
                block();
            }
        }
    }

    // ---------------------------------------------------------------------
    //  Alliance Proposer Behavior (Ticker)
    // ---------------------------------------------------------------------
    private class AllianceProposerBehavior extends TickerBehaviour {
        public AllianceProposerBehavior(Agent a, long period) {
            super(a, period);
        }

        @Override
        protected void onTick() {
            considerProposingAlliance();
        }
    }
    
    // ---------------------------------------------------------------------
    //  Alliances
    // ---------------------------------------------------------------------
    private void considerProposingAlliance() {
        // 1) Identify potential allies
        for (AID potentialAlly : otherPlayers) {
            if (allies.contains(potentialAlly) || rivals.contains(potentialAlly)) {
                continue;
            }
            boolean sharedRival = hasSharedRival(potentialAlly);
            boolean complementaryInventory = hasComplementaryInventory(potentialAlly);

            if (sharedRival || complementaryInventory) {
                // 2) Craft an alliance proposal
                String content = "Proposing an alliance for mutual benefit.";
                if (sharedRival) {
                    content += " We share a common rival, and together we can weaken their market influence.";
                }
                if (complementaryInventory) {
                    content += " Our inventories complement each other, enabling more profitable trades.";
                }

                ACLMessage allianceProposal = new ACLMessage(ACLMessage.PROPOSE);
                allianceProposal.setConversationId("alliance-offer");
                allianceProposal.addReceiver(potentialAlly);
                allianceProposal.setContent(content);
                send(allianceProposal);

                System.out.println(getLocalName() + " proposed an alliance to " + potentialAlly.getLocalName() + ": " + content);
            }
        }
    }

    private void evaluateAllianceProposal(AID proposer, String content) {
        System.out.println(getLocalName() + " evaluating alliance proposal from " + proposer.getLocalName() + ": " + content);
        boolean isReliable = checkReliability(proposer);
        boolean hasMutualBenefit = sharedGoalsOrComplementaryInventory(proposer);

        if (isReliable && hasMutualBenefit) {
            // Accept
            ACLMessage reply = new ACLMessage(ACLMessage.ACCEPT_PROPOSAL);
            reply.setConversationId("alliance-response");
            reply.addReceiver(proposer);
            reply.setContent("Alliance accepted!");
            send(reply);

            allies.add(proposer);
            System.out.println(getLocalName() + " formed an alliance with " + proposer.getLocalName());
        } else {
            // Reject
            ACLMessage reply = new ACLMessage(ACLMessage.REJECT_PROPOSAL);
            reply.setConversationId("alliance-response");
            reply.addReceiver(proposer);
            reply.setContent("Alliance rejected due to lack of alignment.");
            send(reply);

            System.out.println(getLocalName() + " rejected an alliance with " + proposer.getLocalName());
        }
    }

    private boolean hasComplementaryInventory(AID proposer) {
        // Stub logic; randomly returns true/false for demonstration
        return new Random().nextBoolean();
    }

    private boolean hasSharedRival(AID proposer) {
        for (AID rival : rivals) {
            if (rivals.contains(proposer)) {
                return true;
            }
        }
        return false;
    }

    private boolean checkReliability(AID proposer) {
        // Stub logic; randomly returns true/false for demonstration
        return new Random().nextBoolean();
    }

    private boolean sharedGoalsOrComplementaryInventory(AID proposer) {
        return hasSharedRival(proposer) || hasComplementaryInventory(proposer);
    }

    private class AllianceResponder extends CyclicBehaviour {
        @Override
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchConversationId("alliance-offer");
            ACLMessage msg = myAgent.receive(mt);
            if (msg != null) {
                if (msg.getPerformative() == ACLMessage.PROPOSE) {
                    evaluateAllianceProposal(msg.getSender(), msg.getContent());
                }
            } else {
                block();
            }
        }
    }

    // ---------------------------------------------------------------------
    //  Optional sabotage behavior (Ticker)
    // ---------------------------------------------------------------------
    private class SabotageBehavior extends TickerBehaviour {
        public SabotageBehavior(Agent a, long period) {
            super(a, period);
        }

        @Override
        protected void onTick() {
            if (!rivals.isEmpty()) {
                AID target = pickRandomRival();
                ACLMessage deceptionMsg = new ACLMessage(ACLMessage.INFORM);
                deceptionMsg.setConversationId("rumor");
                deceptionMsg.addReceiver(target);
                deceptionMsg.setContent("Rumor: Next round, Clove price will collapse to 1 coin!");
                send(deceptionMsg);
                System.out.println(getLocalName() + " attempts sabotage (false rumor) on "
                                   + target.getLocalName());
            }
        }

        private AID pickRandomRival() {
            List<AID> rList = new ArrayList<>(rivals);
            return rList.get(new Random().nextInt(rList.size()));
        }
    }

    // ---------------------------------------------------------------------
    //  Handling GM "price-announcement", "sell-request", "game-over"
    // ---------------------------------------------------------------------
    private void handlePriceAnnouncement(ACLMessage msg) {
        System.out.println(getLocalName() + " received price-announcement:\n  " + msg.getContent());
        decodePriceEventInfo(msg.getContent());
        updateHistoricalPrices();
        predictNextRoundPrices();
        System.out.println(getLocalName() + ": My naive forecast for next-round prices => " + predictedPrices);
    }

    private void handleSellRequest(ACLMessage msg) {
        System.out.println(getLocalName() + " received sell-request from " + msg.getSender().getLocalName());
        // Store the request until we either succeed or fallback
        pendingSellRequest = msg;

        // If negotiation is already closed (either success or fallback),
        // we can respond immediately
        if (!negotiationOpen) {
            sendSellResponse();
        }
    }

    private void handleGameOver(ACLMessage msg) {
        System.out.println("\n" + getLocalName() + " received 'game-over' from "
                           + msg.getSender().getLocalName()
                           + " with content: " + msg.getContent());
        doDelete(); // Terminate
    }

    // ---------------------------------------------------------------------
    //  Negotiation outcome => Sell strategy
    // ---------------------------------------------------------------------
    private void onNegotiationSuccess() {
        negotiationOpen = false;
        if (pendingSellRequest != null) {
            sendSellResponse();
        }
    }

    private void sendSellResponse() {
        // Build the sell decision
        Map<String, Integer> sellDecision = decideSales(currentPrices, inventory, currentEvent);
        String sellContent = encodeSellInfo(sellDecision);

        ACLMessage reply = pendingSellRequest.createReply();
        reply.setPerformative(ACLMessage.INFORM);
        reply.setConversationId("sell-response");
        reply.setContent(sellContent);
        send(reply);

        System.out.println(getLocalName() + " -> sell-response: " + sellContent);

        // Update local inventory
        updateLocalInventory(sellDecision);

        // Clear out the pending request for next round
        pendingSellRequest = null;
    }

    private Map<String, Integer> decideSales(
            Map<String, Integer> prices,
            Map<String, Integer> myInventory,
            String eventDesc)
    {
        Map<String, Integer> decision = new HashMap<>();
        for (String spice : myInventory.keySet()) {
            int quantity = myInventory.getOrDefault(spice, 0);
            int currentPrice = prices.getOrDefault(spice, 0);
            int predictedNext = predictedPrices.getOrDefault(spice, currentPrice);

            // If next round is likely higher => hold
            if (predictedNext > currentPrice + SELL_DECISION_THRESHOLD) {
                decision.put(spice, 0);
            }
            // If next round is likely lower => sell all
            else if (predictedNext < currentPrice - SELL_DECISION_THRESHOLD) {
                decision.put(spice, quantity);
            }
            // Otherwise, partial sell logic
            else {
                List<Integer> history = historicalPrices.getOrDefault(spice, new ArrayList<>());
                double averagePrice = history.isEmpty() 
                    ? 10 
                    : history.stream().mapToInt(Integer::intValue).average().orElse(10);

                if (currentPrice >= averagePrice) {
                    // Sell half as a simplistic approach
                    decision.put(spice, quantity / 2);
                } else {
                    // Keep
                    decision.put(spice, 0);
                }
            }
        }
        return decision;
    }

    private void updateLocalInventory(Map<String, Integer> sellDecision) {
        for (Map.Entry<String, Integer> entry : sellDecision.entrySet()) {
            String spice = entry.getKey();
            int sellQty = entry.getValue();
            int currentQty = inventory.getOrDefault(spice, 0);
            int newQty = Math.max(0, currentQty - sellQty);
            inventory.put(spice, newQty);
        }
        System.out.println(getLocalName() + " inventory update: " + inventory);
    }

    // ---------------------------------------------------------------------
    //  BDI: Trade Decision Logic
    // ---------------------------------------------------------------------
    private void decideWhatToOfferAndRequest(
            Map<String, Integer> offered,
            Map<String, Integer> requested,
            AID partner)
    {
        // Example strategy:
        String spiceToDrop = null;
        String spiceToHold = null;
        int biggestDrop = 0;
        int biggestRise = 0;

        // Find spice with largest negative diff (predicted - current) => toDrop
        // and largest positive diff => toHold
        for (String spice : currentPrices.keySet()) {
            int now = currentPrices.get(spice);
            int next = predictedPrices.getOrDefault(spice, now);
            int diff = next - now; 
            if (diff < biggestDrop) {
                biggestDrop = diff;
                spiceToDrop = spice;
            }
            if (diff > biggestRise) {
                biggestRise = diff;
                spiceToHold = spice;
            }
        }

        if (spiceToDrop == null || spiceToHold == null || spiceToDrop.equals(spiceToHold)) {
            // fallback
            basicTradeLogic(offered, requested, partner);
            return;
        }

        // Decide quantity
        int availableToOffer = inventory.getOrDefault(spiceToDrop, 0);
        if (availableToOffer <= 0) {
            // fallback
            basicTradeLogic(offered, requested, partner);
            return;
        }

        // Propose up to 2-4 units
        Random rand = new Random();
        int toOffer = Math.min(2 + rand.nextInt(2), availableToOffer);
        offered.put(spiceToDrop, toOffer);

        // Allies get more favorable ratio, rivals get strict ratio, etc.
        if (allies.contains(partner)) {
            requested.put(spiceToHold, Math.max(1, (int)Math.ceil(toOffer / 2.0)));
        }
        else if (rivals.contains(partner)) {
            requested.put(spiceToHold, toOffer); // 1:1 ratio
        }
        else {
            requested.put(spiceToHold, (int)Math.ceil(toOffer * 0.75));
        }
    }

    private void basicTradeLogic(
            Map<String, Integer> offered,
            Map<String, Integer> requested,
            AID partner)
    {
        // 1) Which spice do we have the most of?
        String maxSpice = null;
        int maxQty = 0;
        for (Map.Entry<String, Integer> e : inventory.entrySet()) {
            if (e.getValue() > maxQty) {
                maxQty = e.getValue();
                maxSpice = e.getKey();
            }
        }
        // 2) Which spice has the highest price?
        String highPriceSpice = null;
        int highestPrice = 0;
        for (String spice : currentPrices.keySet()) {
            int price = currentPrices.get(spice);
            if (price > highestPrice) {
                highestPrice = price;
                highPriceSpice = spice;
            }
        }

        if (maxSpice != null && highPriceSpice != null && !maxSpice.equals(highPriceSpice)) {
            int toOffer = Math.min(2, inventory.get(maxSpice));

            if (toOffer > 0) {
                if (allies.contains(partner)) {
                    offered.put(maxSpice, toOffer);
                    requested.put(highPriceSpice, 1);
                } else if (rivals.contains(partner)) {
                    offered.put(maxSpice, toOffer);
                    requested.put(highPriceSpice, toOffer);
                } else {
                    offered.put(maxSpice, toOffer);
                    requested.put(highPriceSpice, (int)Math.ceil(toOffer / 2.0));
                }
            }
        }
    }

    private boolean isTradeAcceptable(Map<String, Integer> offered,
                                      Map<String, Integer> requested,
                                      AID proposer)
    {
        // 1) Check we can supply the requested items
        for (Map.Entry<String, Integer> req : requested.entrySet()) {
            String spice = req.getKey();
            int needed = req.getValue();
            int current = inventory.getOrDefault(spice, 0);
            if (needed > current) {
                return false;
            }
        }

        // 2) Compare approximate "value" using currentPrices
        int valueOffered = 0;
        for (Map.Entry<String, Integer> off : offered.entrySet()) {
            String spice = off.getKey();
            int qty = off.getValue();
            int price = currentPrices.getOrDefault(spice, 0);
            valueOffered += (qty * price);
        }
        int valueRequested = 0;
        for (Map.Entry<String, Integer> req : requested.entrySet()) {
            String spice = req.getKey();
            int qty = req.getValue();
            int price = currentPrices.getOrDefault(spice, 0);
            valueRequested += (qty * price);
        }

        // Allies accept if offered >= 80% 
        if (allies.contains(proposer)) {
            return (valueOffered >= valueRequested * 0.8);
        }
        // Rivals need 120%
        if (rivals.contains(proposer)) {
            return (valueOffered >= valueRequested * 1.2);
        }
        // Others => must be >=
        return (valueOffered >= valueRequested);
    }

    private void executeTrade(Map<String, Integer> offered, Map<String, Integer> requested) {
        // We gain what was offered:
        for (Map.Entry<String, Integer> off : offered.entrySet()) {
            String spice = off.getKey();
            int qty = off.getValue();
            int current = inventory.getOrDefault(spice, 0);
            inventory.put(spice, current + qty);
        }
        // We lose what was requested:
        for (Map.Entry<String, Integer> req : requested.entrySet()) {
            String spice = req.getKey();
            int qty = req.getValue();
            int current = inventory.getOrDefault(spice, 0);
            inventory.put(spice, Math.max(0, current - qty));
        }
    }

    // ---------------------------------------------------------------------
    //  Utility: Encode / Decode
    // ---------------------------------------------------------------------
    private String encodeTradeProposal(Map<String, Integer> offered, Map<String, Integer> requested) {
        // "OFFER:spiceA=2;spiceB=1 -> REQUEST:spiceC=1;spiceD=2"
        StringBuilder sb = new StringBuilder();
        sb.append("OFFER:");
        boolean first = true;
        for (Map.Entry<String, Integer> e : offered.entrySet()) {
            if (!first) sb.append(";");
            sb.append(e.getKey()).append("=").append(e.getValue());
            first = false;
        }
        sb.append(" -> REQUEST:");
        first = true;
        for (Map.Entry<String, Integer> e : requested.entrySet()) {
            if (!first) sb.append(";");
            sb.append(e.getKey()).append("=").append(e.getValue());
            first = false;
        }
        return sb.toString();
    }

    private void decodeTradeProposal(String content, Map<String, Integer> offered, Map<String, Integer> requested) {
        // e.g. "OFFER:Cardamom=2;Cinnamon=1 -> REQUEST:Clove=1;Nutmeg=1"
        try {
            String[] parts = content.split("->");
            if (parts.length == 2) {
                String offerPart = parts[0].replace("OFFER:", "").trim();
                String requestPart = parts[1].replace("REQUEST:", "").trim();

                String[] offerItems = offerPart.split(";");
                for (String o : offerItems) {
                    String[] kv = o.split("=");
                    if (kv.length == 2) {
                        offered.put(kv[0].trim(), Integer.parseInt(kv[1].trim()));
                    }
                }
                String[] requestItems = requestPart.split(";");
                for (String r : requestItems) {
                    String[] kv = r.split("=");
                    if (kv.length == 2) {
                        requested.put(kv[0].trim(), Integer.parseInt(kv[1].trim()));
                    }
                }
            }
        } catch (Exception e) {
            System.err.println(getLocalName() + ": Error parsing trade proposal: " + content);
        }
    }

    private String encodeSellInfo(Map<String, Integer> sellMap) {
        // e.g. "Clove=2;Cinnamon=1;Nutmeg=0;Cardamom=3"
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : sellMap.entrySet()) {
            sb.append(e.getKey()).append("=").append(e.getValue()).append(";");
        }
        if (sb.length() > 0) {
            sb.setLength(sb.length() - 1); // remove trailing semicolon
        }
        return sb.toString();
    }

    private void decodePriceEventInfo(String content) {
        String[] mainParts = content.split("\\|EVENT:");
        if (mainParts.length > 0) {
            String pricePart = mainParts[0];
            for (String spicePair : pricePart.split(";")) {
                String[] kv = spicePair.split("=");
                if (kv.length == 2) {
                    String spiceName = kv[0].trim();
                    int price = Integer.parseInt(kv[1].trim());
                    currentPrices.put(spiceName, price);
                }
            }
        }
        if (mainParts.length > 1) {
            currentEvent = mainParts[1].trim();
        }
    }
}
