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

    private String[] spices = {"Clove", "Cinnamon", "Nutmeg", "Cardamom"};
    private HashMap<String, Integer> spicesPrices = new HashMap<String, Integer>();
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
        spicesPrices.put("Clove", 100);
        spicesPrices.put("Cinnamon", 20);
        spicesPrices.put("Nutmeg", 50);
        spicesPrices.put("Cardamom", 10);
        System.out.println(spicesPrices);
    }


    private class MarketBehaviour extends TickerBehaviour{

        public MarketBehaviour(Agent a, long period) {
            super(a, period);
        }

        private void informMerchants(){
            ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
            msg.setContent(spicesPrices.toString());
            send(msg);
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
            spicesPrices.put("Cinnamon", (int) (spicesPrices.get("Cinnamon") * 1.5));
            System.out.println("[GAMEMASTER]: Updated Price of Cinnamon: "+spicesPrices.get("Cinnamon"));	
        }

        private void eventSultanTax(){
            System.out.println("[GAMEMASTER]: Event: Sultan's Tax");
            System.out.println("[GAMEMASTER]: Fixed 10% tax on all sales.");
            for (String spice : spices) {
                spicesPrices.put(spice, (int) (spicesPrices.get(spice) * 1.1));
            }
            System.out.println("[GAMEMASTER]: Updated Prices: "+spicesPrices);
        }

        private void eventNewRoute(){
            System.out.println("[GAMEMASTER]: Event: New Route Discovered!");
            System.out.println("[GAMEMASTER]: Reduce Price of Most Expensive Spice by 60%");
            int maxPrice = 0;
            String maxSpice = "";
            for (String spice : spices) {
                if (spicesPrices.get(spice) > maxPrice){
                    maxPrice = spicesPrices.get(spice);
                    maxSpice = spice;
                }
            }
            spicesPrices.put(maxSpice, (int) (spicesPrices.get(maxSpice) * 0.4));
            System.out.println("[GAMEMASTER]: Updated Price of "+maxSpice+": "+spicesPrices.get(maxSpice));
        }

        private void updatePrices(){
            for (String spice : spices) {
                float inflationTax = (float) ((random.nextInt(6)-5) * 0.2);
                spicesPrices.put(spice, (int) (spicesPrices.get(spice) * (1 + inflationTax)));
            }
            System.out.println("[GAMEMASTER]: Updated Prices: "+spicesPrices);
            
        }

        @Override
        protected void onTick() {
            updatePrices();
            createEvent();
            informMerchants();
        }

    }

}
