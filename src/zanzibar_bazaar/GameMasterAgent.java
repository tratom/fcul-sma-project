package project;

import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.core.behaviours.TickerBehaviour;
import jade.lang.acl.ACLMessage;

import java.util.HashMap;
import java.util.Random;

import jade.core.Agent;

public class GameMasterAgent extends Agent{

    private static final long serialVersionUID = 1L;
	private String[] spices = {"Clove", "Cinnamon", "Nutmeg", "Cardamom"};
    public HashMap<String, Float> spicesPrices = new HashMap<String, Float>();
    private Random random = new Random();


    @Override
    protected void setup() {
        registerDF();
        System.out.println("Game Master Agent " + getLocalName() + " started.");
        marketSpices();
        addBehaviour(new MarketBehaviour(this, 5000));
    }

    private void registerDF() {
		DFAgentDescription dfd = new DFAgentDescription();
		dfd.setName(getAID());
		ServiceDescription sd = new ServiceDescription();
		sd.setType("GameMaster");
		sd.setName(getLocalName());
		dfd.addServices(sd);
		try {
			DFService.register(this, dfd);
		} catch (FIPAException fe) {
			fe.printStackTrace();
		}
	}

    private void marketSpices() {
        spicesPrices.put("Clove", 100.0f);
        spicesPrices.put("Cinnamon", 20.0f);
        spicesPrices.put("Nutmeg", 50.0f);
        spicesPrices.put("Cardamom", 10.0f);
        System.out.println(spicesPrices);
    }


    private class MarketBehaviour extends TickerBehaviour{

        public MarketBehaviour(Agent a, long period) {
            super(a, period);
        }

        private void createEvent(){
            if (random.nextInt(101) < 50){
                int eventType = random.nextInt(3);
                switch (eventType) {
                    case 0:
                        eventStorm();
                        break;
                    case 1:
                        eventSultanTax();	
                        break;
                    case 2:
                        eventNewRoute();
                        break;
                }
            } else{
                System.out.println("[GAMEMASTER]: No Event");
            } 
        }

        private void eventStorm(){
            System.out.println("[GAMEMASTER]: Event: Storm in the Indian Ocean");
            System.out.println("[GAMEMASTER]: Supply of Cinnamon reduced, Price Will Rise 50% of Current Value");
            spicesPrices.put("Cinnamon", (float)(spicesPrices.get("Cinnamon") * 1.5));
            System.out.println("[GAMEMASTER]: Updated Price of Cinnamon: "+spicesPrices.get("Cinnamon"));	
        }

        private void eventSultanTax(){
            System.out.println("[GAMEMASTER]: Event: Sultan's Tax");
            System.out.println("[GAMEMASTER]: Fixed 10% tax on all sales.");
            for (String spice : spices) {
                spicesPrices.put(spice, (float)(spicesPrices.get(spice) * 1.1));
            }
            System.out.println("[GAMEMASTER]: Updated Prices: "+spicesPrices);
        }

        private void eventNewRoute(){
            System.out.println("[GAMEMASTER]: Event: New Route Discovered!");
            System.out.println("[GAMEMASTER]: Reduce Price of Most Expensive Spice by 60%");
            float maxPrice = 0f;
            String maxSpice = "";
            for (String spice : spices) {
                if (spicesPrices.get(spice) > maxPrice){
                    maxPrice = spicesPrices.get(spice);
                    maxSpice = spice;
                }
            }
            spicesPrices.put(maxSpice, (float)(spicesPrices.get(maxSpice) * 0.4));
            System.out.println("[GAMEMASTER]: Updated Price of "+maxSpice+": "+spicesPrices.get(maxSpice));
        }

        private void updatePrices(){
            for (String spice : spices) {
                float inflationTax = (float) ((random.nextInt(5)-2) * 0.2);
                spicesPrices.put(spice, (float)(spicesPrices.get(spice) * (1 + inflationTax)));
            }
            System.out.println("[GAMEMASTER]: Updated Prices: "+spicesPrices);
            
        }
        
        
        private void requestActionsFromMerchants() {
    		DFAgentDescription template = new DFAgentDescription();
    		ServiceDescription sd = new ServiceDescription();
    		sd.setType("Merchant");
    		template.addServices(sd);
    		
    		try {
    			DFAgentDescription[] result = DFService.search(myAgent, template);
    			ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
    			request.setContent("Submit your action for this round");
    	        
    	        for (DFAgentDescription merchant : result) {
    	            request.addReceiver(merchant.getName());
    	        }
    	        send(request);
    		} catch (FIPAException e) {
    			e.printStackTrace();
    		}
    	}
        
        private void processMerchantActions() {
        	ACLMessage msg;
            while ((msg = receive()) != null) {
                String content = msg.getContent();
                if (content.startsWith("MarketPrice:")) {
                    String spice = content.split(":")[1];
                    if (spicesPrices.containsKey(spice)) {
                        float price = spicesPrices.get(spice);
                        ACLMessage reply = msg.createReply();
                        reply.setPerformative(ACLMessage.INFORM);
                        reply.setContent(String.valueOf(price));
                        send(reply);
                        System.out.println("[GAMEMASTER]: Sent market price of " + spice + ": " + price + " to " + msg.getSender().getLocalName());
                    } else {
                        System.out.println("[GAMEMASTER]: Spice not found: " + spice);
                    }
                } else if (content.startsWith("Sell")) {
                    String[] parts = content.split(":");
                    String spice = parts[1].trim();
                    int quantity = Integer.parseInt(parts[2].trim());
                    spicesPrices.put(spice, spicesPrices.get(spice) - (quantity * 0.5f));
                } else if (content.startsWith("Trade")) {
                    System.out.println("[GAMEMASTER]: Trade recorded: " + content);
                }else {
                    System.out.println("[GAMEMASTER]: Unhandled message: " + content);
                }
            }
        }
        	

        @Override
        protected void onTick() {
            updatePrices();
            createEvent();
            requestActionsFromMerchants();
            processMerchantActions();
        }
    }
}
