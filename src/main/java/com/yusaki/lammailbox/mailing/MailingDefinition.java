package com.yusaki.lammailbox.mailing;

import com.yusaki.lammailbox.model.CommandItem;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class MailingDefinition {
    private final String id;
    private final MailingType type;
    private final boolean enabled;
    private final String receiver;
    private final String message;
    private final String sender;
    private final List<String> itemDirectives;
    private final List<String> commands;
    private final List<CommandItem> commandItems;
    private final String cronExpression;
    private final Integer maxRuns;
    private final Integer expireDays;
    private final String requiredPermission;
    private final Duration firstJoinDelay;

    private MailingDefinition(Builder builder) {
        this.id = builder.id;
        this.type = builder.type;
        this.enabled = builder.enabled;
        this.receiver = builder.receiver;
        this.message = builder.message;
        this.sender = builder.sender;
        this.itemDirectives = Collections.unmodifiableList(new ArrayList<>(builder.itemDirectives));

        List<CommandItem> builtCommandItems = new ArrayList<>(builder.commandItems);
        this.commandItems = Collections.unmodifiableList(builtCommandItems);

        List<String> aggregateCommands = new ArrayList<>(builder.commands);
        if (!builtCommandItems.isEmpty()) {
            Set<String> ordered = new LinkedHashSet<>(aggregateCommands);
            for (CommandItem item : builtCommandItems) {
                ordered.addAll(item.commands());
            }
            aggregateCommands = new ArrayList<>(ordered);
        }
        this.commands = Collections.unmodifiableList(aggregateCommands);
        this.cronExpression = builder.cronExpression;
        this.maxRuns = builder.maxRuns;
        this.expireDays = builder.expireDays;
        this.requiredPermission = builder.requiredPermission;
        this.firstJoinDelay = builder.firstJoinDelay;
    }

    public String id() {
        return id;
    }

    public MailingType type() {
        return type;
    }

    public boolean enabled() {
        return enabled;
    }

    public String receiver() {
        return receiver;
    }

    public String message() {
        return message;
    }

    public String sender() {
        return sender;
    }

    public List<String> itemDirectives() {
        return itemDirectives;
    }

    public List<String> commands() {
        return commands;
    }

    public List<CommandItem> commandItems() {
        if (!commandItems.isEmpty()) {
            return commandItems;
        }
        if (commands.isEmpty()) {
            return Collections.emptyList();
        }
        List<CommandItem> legacy = new ArrayList<>();
        for (String command : commands) {
            legacy.add(CommandItem.legacyFromCommand(command));
        }
        return legacy;
    }

    public String cronExpression() {
        return cronExpression;
    }

    public Integer maxRuns() {
        return maxRuns;
    }

    public Integer expireDays() {
        return expireDays;
    }

    public String requiredPermission() {
        return requiredPermission;
    }

    public Duration firstJoinDelay() {
        return firstJoinDelay;
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public static final class Builder {
        private final String id;
        private MailingType type = MailingType.REPEATING;
        private boolean enabled = true;
        private String receiver = "all";
        private String message = "";
        private String sender = "Console";
        private List<String> itemDirectives = new ArrayList<>();
        private List<String> commands = new ArrayList<>();
        private List<CommandItem> commandItems = new ArrayList<>();
        private String cronExpression;
        private Integer maxRuns;
        private Integer expireDays;
        private String requiredPermission;
        private Duration firstJoinDelay;

        private Builder(String id) {
            this.id = Objects.requireNonNull(id, "id");
        }

        public Builder type(MailingType type) {
            this.type = type;
            return this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder receiver(String receiver) {
            if (receiver != null && !receiver.isBlank()) {
                this.receiver = receiver;
            }
            return this;
        }

        public Builder message(String message) {
            if (message != null) {
                this.message = message;
            }
            return this;
        }

        public Builder sender(String sender) {
            if (sender != null && !sender.isBlank()) {
                this.sender = sender;
            }
            return this;
        }

        public Builder itemDirectives(List<String> itemDirectives) {
            if (itemDirectives != null) {
                this.itemDirectives = new ArrayList<>(itemDirectives);
            }
            return this;
        }

        public Builder commands(List<String> commands) {
            if (commands != null) {
                this.commands = new ArrayList<>(commands);
            }
            return this;
        }

        public Builder commandItems(List<CommandItem> commandItems) {
            if (commandItems != null) {
                this.commandItems = new ArrayList<>(commandItems);
            }
            return this;
        }

        public Builder expireDays(Integer expireDays) {
            this.expireDays = expireDays;
            return this;
        }

        public Builder requiredPermission(String requiredPermission) {
            if (requiredPermission != null && !requiredPermission.isBlank()) {
                this.requiredPermission = requiredPermission;
            }
            return this;
        }

        public Builder cronExpression(String cronExpression) {
            if (cronExpression != null && !cronExpression.isBlank()) {
                this.cronExpression = cronExpression.trim();
            }
            return this;
        }

        public Builder maxRuns(Integer maxRuns) {
            if (maxRuns != null && maxRuns > 0) {
                this.maxRuns = maxRuns;
            }
            return this;
        }

        public Builder firstJoinDelay(Duration firstJoinDelay) {
            this.firstJoinDelay = firstJoinDelay;
            return this;
        }

        public MailingDefinition build() {
            return new MailingDefinition(this);
        }
    }
}
