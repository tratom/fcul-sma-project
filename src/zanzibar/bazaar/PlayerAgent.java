package zanzibar.bazaar;

import jade.core.Agent;
import jade.core.AID;
import jade.core.behaviours.*;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
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
 *    and attempts trades up to a fallback.
 *  - Has a AllianceProposerBehavior that propose alliances each tick.
 *  - Responds to trade/alliance proposals from other players.
 *  - When the GM requests a sell decision, the agent replies only after
 *    at least one negotiation has succeeded or the fallback is triggered.
 */
public class PlayerAgent extends Agent {

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

    /** Current coins on hand. */
    private int coins = 0; // Starting coins initialized in setup

    /** 
     * For demonstration: we track alliances and rivals. 
     * Allies are given favorable trades; rivals, unfavorable ones.
     */
    private Set<AID> allies = new HashSet<>();
    private Set<AID> rivals = new HashSet<>();

    /** 
     * List of known players in the DF (besides self).
     */
    private Set<AID> otherPlayers = new HashSet<>();

    // ------------------------------------------------
    // BDI: Desires (high-level goals)
    // ------------------------------------------------
    /**
     * Desire: "Maximize short-term profit."
     * Desire: "Avoid selling spices that will increase in price next round."
     * Desire: "Capitalize on events that predict higher future prices."
     * Desire: "Form alliances with players who offer beneficial trades."
     * Desire: "Harm or mislead rivals if beneficial."
     *
     * In code, these are not single variables but guide logic in how
     * we propose/accept trades and how we decide on final sales.
     */

    // ------------------------------------------------
    //  Intentions & Execution Control
    // ------------------------------------------------

    /**
     * negotiationOpen: Are we allowed to negotiate trades right now?
     */
    private boolean negotiationOpen = false;

    /**
     * Maximum number of attempts to initiate a trade. After these attempts,
     * if no success, we fallback and end negotiation.
     */
    private static final int MAX_NEGOTIATION_ATTEMPTS = 3;
    private int negotiationAttempts = 0;

    /** How often we spontaneously propose trades (ms). */
    private static final long NEGOTIATION_INTERVAL = 2000;

    /** How often we spontaneously propose alliances (ms). */
    private static final long ALLIANCE_INTERVAL = 10000;

    /** Price difference between current price and prediction to make the sell decision */
    private static final int SELL_DECISION_THRESHOLD = 3;

    /**
     * If the GM requests a "sell decision," we store it here until we are
     * done negotiating or hit fallback.
     */
    private ACLMessage pendingSellRequest = null;

    // ------------------------------------------------
    // Setup / Initialization
    // ------------------------------------------------
    @Override
    protected void setup() {
        System.out.println(getLocalName() + " is starting. Registering as zanzibar-player...");

        // 1) Register as player
        registerAsPlayer("zanzibar-player");

        // 2) Initialize random inventory
        Random rand = new Random();
        // Each spice from 1..5 quantity (change as desired)
        inventory.put(BazaarAgent.CLOVE,    1 + rand.nextInt(5));  // The most valuable and rare of spices, difficult to obtain.
        inventory.put(BazaarAgent.CINNAMON, 1 + rand.nextInt(30)); // Common, but essential to maintain a steady stream of profits.
        inventory.put(BazaarAgent.NUTMEG,   1 + rand.nextInt(10)); // Highly valued, especially with the arrival of new European merchants.
        inventory.put(BazaarAgent.CARDAMOM, 1 + rand.nextInt(25)); // A basic product, but subject to price fluctuations due to its variable demand.

        // 4) Initialize historicalPrices structure so we don’t get NPE.
        for (String spice : Arrays.asList(BazaarAgent.CLOVE, BazaarAgent.CINNAMON,
                                          BazaarAgent.NUTMEG, BazaarAgent.CARDAMOM)) {
            historicalPrices.put(spice, new ArrayList<>());
        }
        
        // 5) Initialize randomize coins from e.g. 30..80
        // coins = 30 + rand.nextInt(51); TODO: manage wallet to buy spices from other merchant during trades

        // 6) Add Behaviors:
        addBehaviour(new MarketMessageListener());    // GM messages
        addBehaviour(new NegotiationResponder());     // handle trade offers
        addBehaviour(new AllianceResponder());        // handle alliance proposals
        addBehaviour(new SabotageBehavior(this, 15000)); // optional sabotage
        addBehaviour(new NegotiationInitiatorBehavior(this, NEGOTIATION_INTERVAL));
        addBehaviour(new AllianceProposerBehavior(this, ALLIANCE_INTERVAL));

        System.out.println(getLocalName() + " setup complete:");
        System.out.println(getLocalName() + " - initial inventory: " + inventory);
        // System.out.println(getLocalName() + " - initial coins    : " + coins);
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
    /**
     * In BDI terms, we update our beliefs about:
     *  - Current event
     *  - Current prices
     *  - Historical prices
     *  whenever we receive a "price-announcement".
     */
    private void updateHistoricalPrices() {
        // Store to historical record
        for (Map.Entry<String, Integer> entry : currentPrices.entrySet()) {
            String spice = entry.getKey();
            int price = entry.getValue();
            historicalPrices.get(spice).add(price);
        }
    }

    /**
     * A simple “predictive” method that tries to forecast next-round prices.
     * Can be improved with machine learning or more advanced models.
     * For demonstration, we do a naive approach:
     *    - If there's a "Storm" on a spice, predict price + X for next round
     *    - If there's a "New Trade Route" on a spice, predict price * factor for next round
     *    - Else, extrapolate a small fraction from the last two historical points
     */
    private void predictNextRoundPrices() {
    	this.predictedPrices.clear();
        // We’ll keep track if we have a single “affectedSpice”
        // so we can skip extrapolating that spice in step (4).
        String spiceImpacted = null; 
                
        // 1) Start with current price as baseline
        for (Map.Entry<String, Integer> entry : currentPrices.entrySet()) {
            predictedPrices.put(entry.getKey(), entry.getValue());
        }

        // 2) If there is a Storm event, parse out spice +X
        //    Adjust predictions if there's an explicit event
        //    (the actual effect is also done by BazaarAgent, but we replicate it in beliefs)
        if (currentEvent.contains("Storm in Indian Ocean")) {
            // e.g. "Storm in Indian Ocean: Next round Cinnamon price +5"
            try {
                String[] parts = currentEvent.split("Next round")[1].trim().split(" ");
                // parts[0] = "Cinnamon"
                // parts[1] = "price"
                // parts[2] = "+5"
                spiceImpacted = parts[0];
                String plusStr = parts[2]; // e.g. "+5"
                int plusAmount = Integer.parseInt(plusStr.replace("+", ""));
                int oldPrice = predictedPrices.getOrDefault(spiceImpacted, 1);
                predictedPrices.put(spiceImpacted, Math.max(1, oldPrice + plusAmount));
            } catch (Exception e) {
                System.err.println(getLocalName() + ": Error parsing storm event => " + e);
            }
            
        // 3) If there is a New Trade Route event, parse spice xFactor
        //    Adjust predictions if there's an explicit event
        //    (the actual effect is also done by BazaarAgent, but we replicate it in beliefs)
        } else if (currentEvent.contains("New Trade Route Discovered")) {
            // e.g. "New Trade Route Discovered: Clove next round price x0.4"
            try {
                String[] parts = currentEvent.split(":")[1].trim().split(" ");
                // parts[0] = "Clove"
                // parts[4] = "x0.4"
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

        // 4) For all other spices (not the spice impacted from the event9,
        // if we have at least 2 historical price points, 
        // we do a naive linear extrapolation to adjust the predicted price
        for (String spice : predictedPrices.keySet()) {
        	// skip the event-affected spice
            if (spice.equals(spiceImpacted)) {
                continue;
            }
            List<Integer> hist = historicalPrices.get(spice);
            if (hist.size() >= 2) {
                int last = hist.get(hist.size() - 1);
                int prev = hist.get(hist.size() - 2);
                int naiveNext = last + (last - prev) / 2;  // a mild extrapolation
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

                        // Reset negotiation attempts
                        negotiationAttempts = 0;
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

            negotiationAttempts++;

            // If we've reached the max attempts with no success, fallback
            if (negotiationAttempts > MAX_NEGOTIATION_ATTEMPTS) {
                System.out.println(getLocalName()
                        + " reached max negotiation attempts without success. "
                        + "Fallback: ending negotiation phase and forcing sell if pending.");
                negotiationOpen = false;
                // If a sell request is pending, respond now
                if (pendingSellRequest != null) {
                    sendSellResponse();
                }
                return;
            }

            // Attempt a trade if we have other players and some inventory
            if (!otherPlayers.isEmpty() && !inventory.isEmpty()) {
                AID partner = pickRandomPlayer();
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
                } else {
                    System.out.println(getLocalName() + ": No valid trade offer to propose at this time.");
                }
            } else {
                System.out.println(getLocalName() + ": Negotiation not possible (no players or no inventory).");
                negotiationOpen = false;
                // If a sell request is pending, respond now
                if (pendingSellRequest != null) {
                    sendSellResponse();
                }
                return;
            }
        }

        private AID pickRandomPlayer() {
            List<AID> list = new ArrayList<>(otherPlayers);
            return list.get(new Random().nextInt(list.size()));
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

                        // Mark that we had a successful negotiation => end negotiation phase
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
    //  Negotiation / Trade Initiation Behavior (Ticker)
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
	//  Alliance Strategy Expansion
	// ---------------------------------------------------------------------
	/**
	 * Decide whether to propose an alliance and to whom.
	 * Includes logic for identifying allies based on inventory, goals, and rivals.
	 */
	private void considerProposingAlliance() {
	    // 1) Identify potential allies
	    for (AID potentialAlly : otherPlayers) {
	        if (allies.contains(potentialAlly) || rivals.contains(potentialAlly)) {
	            continue; // Skip if already allied or a known rival
	        }
	
	        boolean sharedRival = hasSharedRival(potentialAlly);
	        boolean complementaryInventory = hasComplementaryInventory(potentialAlly);
	
	        if (sharedRival || complementaryInventory) {
	            // 2) Craft a tailored alliance proposal
	            String content = "Proposing an alliance for mutual benefit.";
	            if (sharedRival) {
	                content += " We share a common rival, and together we can weaken their market influence.";
	            }
	            if (complementaryInventory) {
	                content += " Our inventories complement each other, allowing for more profitable trades.";
	
	                ACLMessage allianceProposal = new ACLMessage(ACLMessage.PROPOSE);
	                allianceProposal.setConversationId("alliance-offer");
	                allianceProposal.addReceiver(potentialAlly);
	                allianceProposal.setContent(content);
	                send(allianceProposal);
	
	                System.out.println(getLocalName() + " proposed an alliance to " + potentialAlly.getLocalName() + ": " + content);
	            }
	        }
	    }
	}
	
	/**
	 * Evaluate incoming alliance proposals systematically.
	 * Decide whether to accept or reject based on proposer characteristics and strategy alignment.
	 */
	private void evaluateAllianceProposal(AID proposer, String content) {
	    System.out.println(getLocalName() + " evaluating alliance proposal from " + proposer.getLocalName() + ": " + content);
	
	    // Assess proposer’s reliability and inventory
	    boolean isReliable = checkReliability(proposer);
	    boolean hasMutualBenefit = sharedGoalsOrComplementaryInventory(proposer);
	
	    if (isReliable && hasMutualBenefit) {
	        // Accept the alliance
	        ACLMessage reply = new ACLMessage(ACLMessage.ACCEPT_PROPOSAL);
	        reply.setConversationId("alliance-response");
	        reply.addReceiver(proposer);
	        reply.setContent("Alliance accepted!");
	        send(reply);
	
	        allies.add(proposer);
	        System.out.println(getLocalName() + " formed an alliance with " + proposer.getLocalName());
	    } else {
	        // Reject the alliance
	        ACLMessage reply = new ACLMessage(ACLMessage.REJECT_PROPOSAL);
	        reply.setConversationId("alliance-response");
	        reply.addReceiver(proposer);
	        reply.setContent("Alliance rejected due to lack of alignment.");
	        send(reply);
	
	        System.out.println(getLocalName() + " rejected an alliance with " + proposer.getLocalName());
	    }
	}
	
	/**
	 * Check if the proposer has complementary inventory.
	 */
	private boolean hasComplementaryInventory(AID proposer) {
	    // Simplified for demonstration: Assume we query their inventory
	    // In a real system, we’d either observe trades or ask directly
	    // For now, return true randomly for simplicity
	    return new Random().nextBoolean();
	}
	
	/**
	 * Check if the proposer shares a rival with this agent.
	 */
	private boolean hasSharedRival(AID proposer) {
	    for (AID rival : rivals) {
	        if (rivals.contains(proposer)) {
	            return true;
	        }
	    }
	    return false;
	}
	
	/**
	 * Check the reliability of a potential ally based on past interactions.
	 */
	private boolean checkReliability(AID proposer) {
	    // Simplified: Assume we have a record of past trades/interactions
	    // Return true randomly for demonstration
	    return new Random().nextBoolean();
	}
	
	/**
	 * Check if the proposer’s goals align with this agent’s strategy.
	 */
	private boolean sharedGoalsOrComplementaryInventory(AID proposer) {
	    return hasSharedRival(proposer) || hasComplementaryInventory(proposer);
	}
    
    // ---------------------------------------------------------------------
    //  Alliances: Responder to alliance proposals (Cyclic)
    // ---------------------------------------------------------------------
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
        // Decode the new prices + event
        decodePriceEventInfo(msg.getContent());
        // Here we update our BDI beliefs
        updateHistoricalPrices();

        // Do an early “desire formation” or “prediction” step
        // storing the results in a class variable to guide decisions.
        predictNextRoundPrices();
        // Print it for demonstration
        System.out.println(getLocalName() + ": My naive forecast for next-round prices => " + predictedPrices);
    }

    private void handleSellRequest(ACLMessage msg) {
        System.out.println(getLocalName() + " received sell-request from " + msg.getSender().getLocalName());
        // Store this message until we either succeed in a negotiation or we fallback
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
        // Terminate this agent
        doDelete();
    }

    // ---------------------------------------------------------------------
    //  BDI: Decision-Making (After Negotiations) -> Sell Strategy
    // ---------------------------------------------------------------------
    /**
     * Called when a negotiation is successful (trade accepted).
     * We close negotiation and if we have a pending sell-request, we respond now.
     */
    private void onNegotiationSuccess() {
        negotiationOpen = false;
        // If we have a pending sell request, respond
        if (pendingSellRequest != null) {
            sendSellResponse();
        }
    }

    /**
     * Build and send the sell response. Then reset round-based states if needed.
     */
    private void sendSellResponse() {
        // We apply our BDI-based “selling strategy”:
        Map<String, Integer> sellDecision = decideSales(currentPrices, inventory, currentEvent);
        String sellContent = encodeSellInfo(sellDecision);

        ACLMessage reply = pendingSellRequest.createReply();
        reply.setPerformative(ACLMessage.INFORM);
        reply.setConversationId("sell-response");
        reply.setContent(sellContent);
        send(reply);

        System.out.println(getLocalName() + " -> sell-response: " + sellContent);

        // Update local inventory (remove what we sold)
        updateLocalInventory(sellDecision);

        // Clear out the pending request for next round
        pendingSellRequest = null;
    }

    /**
     * BDI-inspired selling logic:
     *  - If an upcoming Storm on Spice X is forecast to raise its price, hold it.
     *  - If a new trade route will reduce next price for Spice Y, sell now.
     *  - Otherwise, if price is already good (>= 10), sell half to secure profit.
     *  - (Adapt as you wish for more advanced logic)
     */
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

            // Check if next round is likely higher => hold
            if (predictedNext > currentPrice + SELL_DECISION_THRESHOLD) {
                decision.put(spice, 0);
            }
            // If next round is likely lower => dump now
            else if (predictedNext < currentPrice - SELL_DECISION_THRESHOLD) {
                decision.put(spice, quantity);
            }
            // If about the same or no strong difference, compare to historical average
            else {
                List<Integer> history = historicalPrices.getOrDefault(spice, new ArrayList<>());
                double averagePrice = history.isEmpty() ? 10 : history.stream().mapToInt(Integer::intValue).average().orElse(10);
                
                if (currentPrice >= averagePrice) {
                    // Sell the half
                    decision.put(spice, Math.min(1, predictedNext / 3));
                } else {
                    // Keep it
                    decision.put(spice, 0);
                }
            }
        }

        return decision;
    }

    /** Remove sold items from local inventory. */
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
    /**
     * Decide what to offer vs. what we request in a trade proposal.
     * Possibly factor in alliances or rivalries for ratio changes.
     *
     * In a BDI sense, we are forming an "intention" to get rid of spices we
     * believe will drop in price, while acquiring those we believe will rise.
     */
    private void decideWhatToOfferAndRequest(
            Map<String, Integer> offered,
            Map<String, Integer> requested,
            AID partner)
    {
    	
        // Example:
        // 1) Identify a spice in our inventory we *predict will drop* next round
        // 2) Identify a spice we *predict will rise* next round
        // Then propose a ratio depending on alliance or rivalry.

        String spiceToDrop = null;
        String spiceToHold = null;
        int biggestDrop = 0;
        int biggestRise = 0;

        // Find the spice with the largest negative (predicted - current)
        // and the largest positive (predicted - current).
        for (String spice : currentPrices.keySet()) {
            int now = currentPrices.get(spice);
            int next = predictedPrices.get(spice);

            int diff = next - now; // negative => price drop, positive => price rise
            if (diff < biggestDrop) {
                biggestDrop = diff;
                spiceToDrop = spice;
            }
            if (diff > biggestRise) {
                biggestRise = diff;
                spiceToHold = spice;
            }
        }

        // If we can’t find any meaningful difference, fallback to original logic:
        if (spiceToDrop == null || spiceToHold == null || spiceToDrop.equals(spiceToHold)) {
            // fallback to a simpler approach:
            basicTradeLogic(offered, requested, partner);
            return;
        }

        // Decide quantity:
        int availableToOffer = inventory.getOrDefault(spiceToDrop, 0);
        if (availableToOffer <= 0) {
            // fallback
            basicTradeLogic(offered, requested, partner);
            return;
        }

        // Propose to trade up to 4 units of the "drop" spice
        Random rand = new Random();
        int toOffer = Math.min(2 + rand.nextInt(2), availableToOffer);
        offered.put(spiceToDrop, toOffer);

        // Decide how many to request of the "rising" spice
        // Allies get more favorable ratio, rivals more demanding
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

    /**
     * A fallback method that picks the spice we have the most of (offer)
     * and the highest-priced spice in the market (request).
     */
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
                // Allies get more favorable ratio
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

    /**
     * Check if the trade is acceptable:
     *  1) We have enough inventory to give what is requested.
     *  2) Compare approximate "value" from currentPrices.
     *  3) Adjust acceptance threshold for allies or rivals.
     */
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

        // 2) Sum up approximate value
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

        // 3) Allies might accept slightly unfavorable deals
        if (allies.contains(proposer)) {
            // Accept if offered >= 80% of requested
            return (valueOffered >= valueRequested * 0.8);
        }
        // Rivals might require strictly better deals
        if (rivals.contains(proposer)) {
            // Accept if offered >= 120% of requested
            return (valueOffered >= valueRequested * 1.2);
        }
        // Otherwise, standard check
        return (valueOffered >= valueRequested);
    }

    /**
     * Apply the trade in our local inventory:
     *  - We GAIN what was offered
     *  - We LOSE what we requested
     */
    private void executeTrade(Map<String, Integer> offered, Map<String, Integer> requested) {
        // Add offered to our inventory
        for (Map.Entry<String, Integer> off : offered.entrySet()) {
            String spice = off.getKey();
            int qty = off.getValue();
            int current = inventory.getOrDefault(spice, 0);
            inventory.put(spice, current + qty);
        }
        // Remove requested from our inventory
        for (Map.Entry<String, Integer> req : requested.entrySet()) {
            String spice = req.getKey();
            int qty = req.getValue();
            int current = inventory.getOrDefault(spice, 0);
            inventory.put(spice, Math.max(0, current - qty));
        }
    }

    // ---------------------------------------------------------------------
    //  Utility: Encode / Decode and minor methods
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

    /**
     * E.g. "Clove=20;Cinnamon=10;Nutmeg=15;Cardamom=5|EVENT:Storm in Indian Ocean..."
     */
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
