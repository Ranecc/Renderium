package com.ranecc.renderium.infrastructure.config.structure;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RendererOptionPage implements RendererPage {

    private final Component name;
    private final List<RendererOptionGroup> groups = new ArrayList<>();

    protected RendererOptionPage(Component name) {
        this.name = name;
    }

    public RendererOptionPage(Component name, List<RendererOptionGroup> groups) {
        this.name = name;
        if (groups != null) this.groups.addAll(groups);
    }

    @Override
    public Component getName() { return name; }

    public Component name() { return name; }

    protected void addGroup(RendererOptionGroup group) {
        if (group != null) {
            groups.add(group);
        }
    }

    public List<RendererOptionGroup> getGroups() {
        return Collections.unmodifiableList(groups);
    }

    public List<RendererOptionGroup> groups() {
        return getGroups();
    }

    @Override
    public void registerTextSources(SearchIndex index, Object modOptions) {
        for (RendererOptionGroup group : groups) {
            for (RendererOption option : group.options()) {
                if (option instanceof SearchableOption searchable) {
                    index.register(new OptionTextSource(searchable, group));
                }
            }
        }
    }
}
