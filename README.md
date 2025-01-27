# Zanzibar Bazaar - A Multi-Agent System Project

This repository contains our implementation of the **Zanzibar Bazaar** multi-agent system using the [JADE](http://jade.tilab.com/) framework. In this scenario, multiple merchant agents compete in a spice market, while a **BazaarAgent** (Game Master) orchestrates rounds, announces events, and calculates player scores.

---

## Table of Contents

1. [Overview](#overview)  
2. [Project Structure](#project-structure)  
3. [Requirements](#requirements)  
4. [Running the Project](#running-the-project)  
5. [Agents Description](#agents-description)  
6. [Author and Contact](#author-and-contact)  

---

## Overview

**Zanzibar Bazaar** simulates a historical spice market where merchants attempt to maximize profits by trading, forming alliances, and reacting to random events (storms, port taxes, new trade routes). The system demonstrates:
- **BDI-inspired** agents (AdvancedPlayerAgent) that track beliefs (prices, events), form desires (maximize profit, sabotage rivals), and execute intentions (propose trades, accept or reject offers).
- A **BazaarAgent** that manages each round, updates prices based on supply/demand, and introduces game-ending conditions.
- A simpler agent (SimplePlayerAgent), which uses minimal logic (random or coin-flip acceptance), serving as a baseline for comparison against the BDI-capable PlayerAgents.

---

## Project Structure

A typical directory layout might look like this:
```
zanzibar-bazaar/
├─ docs/
|   ├─ Project_Presentation.pdf // slides
|   └─ Project_Report.pdf // final report
├─ simulation/
|   ├─ output.txt // output log
|   ├─ screen_record.mkv // video capture of the running simulation
|   ├─ sniffer_snapshot_file.txt // canva of message exchange, can be opened from the sniffer
|   └─ sniffer_message_list // human readable message exchange list
├─ src/
│   ├─ zanzibar/bazaar/
│   │   ├─ BazaarAgent.java // The Game Master agent
│   │   ├─ AdvancedPlayerAgent.java // BDI-like merchant agent
│   │   └─ SimplePlayerAgent.java // Simple merchant agent with minimal logic
└─ README.md // This file
```
- **`BazaarAgent.java`**: Coordinates rounds, broadcasts events, calculates scores, and applies taxes or price changes.  
- **`AdvancedPlayerAgent.java`**: A BDI-like agent with inventory tracking, event forecasting, alliances/rivalries, and negotiation.  
- **`SimplePlayerAgent.java`**: A simpler agent that randomly makes or accepts trade proposals, illustrating minimal strategy.

---

## Requirements

- **Java 8+** (tested up to Java 11 or higher)
- **JADE 4.6.0** or compatible version  
  Download from: [JADE website](http://jade.tilab.com/)

To compile and run, ensure you have both `jade.jar` and `jadeTools.jar` (Sniffer agent) in your classpath.

---

## Running the Project

### 1. Clone or Download the Repository
```bash
git clone https://github.com/YourUsername/zanzibar-bazaar.git
cd zanzibar-bazaar
```

### 2. Compile the JADE Agents
You can use a build tool (Maven/Gradle) or directly compile with `javac`, ensuring the classpath references JADE:
```bash
javac -cp .:path/to/jade.jar zanzibar/bazaar/*.java
```

### 3. Launch the JADE Runtime
Run JADE's main container, specifying the classpath. For example:
```bash
java -cp .:path/to/jade.jar jade.Boot -gui
```
Alternatively, you can start the system via a script or an IDE run configuration.

### 4. Start the Agents
You can either start them from the JADE RMA (Remote Monitoring Agent) or specify them at launch. For example:
```bash
java -cp .:path/to/jade.jar:./build zanzibar.bazaar.BazaarAgent -maxRounds 5
java -cp .:path/to/jade.jar:./build zanzibar.bazaar.AdvancedPlayerAgent
java -cp .:path/to/jade.jar:./build zanzibar.bazaar.SimplePlayerAgent
```
Adjust the classpath and package names as needed.

### 5. Monitor with Sniffer (Optional)
Add `-services jade.core.messaging.TraceService` or use the JADE GUI’s Sniffer to watch message exchanges.

---

## Agents Description

### 1. BazaarAgent

- **Registration:** Registers in the Directory Facilitator as `bazaar-master`.
- **Responsibilities:**
  - Conducts the marketplace rounds:
    - Update Player List (DF check).
    - Announce Round Start.
    - Announce Prices (and random events).
    - Request Sales.
    - Collect Sales and update the scoreboard.
    - End Round and adjust market prices.
  - Terminates the game with a game-over message after reaching `maxRounds`.

---

### 2. PlayerAgent (BDI-like)

- **Beliefs:** Maintains knowledge of current prices, events, inventory, and known players.
- **Desires:** Aims to maximize profit, form alliances, and sabotage rivals.
- **Intentions:**
  - NegotiationInitiatorBehavior to propose trades.
  - NegotiationResponder to accept/reject trades.
  - AllianceProposer and AllianceResponder.
  - SabotageBehavior (optional).
- **Decision-making:** Uses naive forecasting to decide whether to hold or sell each spice.

---

### 3. MerchantAgent (Simple)

- **Registration:** Similar DF registration as `zanzibar-player`.
- **Behavior:**
  - Maintains a budget and a small inventory.
  - Randomly proposes or accepts trades (50% acceptance).
  - Picks random quantities to sell when responding to the sell-request.

These three agent types interact in the same environment, showcasing how different negotiation and forecasting strategies yield diverse outcomes.

---

## Author and Contact

- **Tommaso Tragno** - [tommaso.tragno@gmail.com](mailto:tommaso.tragno@gmail.com)
- **Duarte Alexandre Pedro Gonçalves** - [duarte.dapg@gmail.com](mailto:duarte.dapg@gmail.com)

For any questions or suggestions, feel free to open an issue or reach out by email. Pull requests to improve or extend the simulation are also welcome!
