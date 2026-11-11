package com.pams.module.agent.core;

/** Resumes an existing interaction; its plan/form already belongs to a durable task.
 * The outcome handler must not create a second task or replace the business tool identity. */
public interface PersistedInteractionTool extends AgentTool {}
