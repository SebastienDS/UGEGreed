package fr.uge.greed.packet;

import fr.uge.greed.Payload;

public record AnnulationTask(long id, long startRemainingValues) implements Payload {}
