package com.ranecc.renderium.infrastructure.config.structure;

@SuppressWarnings({"rawtypes", "unchecked"})
public class EnumOption<E extends Enum<E>> implements RendererOption {
    private E value;
    private final Class<E> enumClass;
    private final java.util.Map<E, net.minecraft.network.chat.Component> names;

    public EnumOption(Class<E> enumClass) {
        this.enumClass = enumClass;
        this.names = new java.util.HashMap<>();
        if (enumClass.getEnumConstants() != null && enumClass.getEnumConstants().length > 0) {
            this.value = enumClass.getEnumConstants()[0];
        }
    }

    public E getValue() { return value; }
    public void setValue(E v) { this.value = v; }
    public boolean isEnabled(ConfigState state) { return true; }

    public void setElementName(E e, net.minecraft.network.chat.Component name) {
        names.put(e, name);
    }

    public net.minecraft.network.chat.Component getElementName(E e) {
        return names.getOrDefault(e, net.minecraft.network.chat.Component.literal(e.name()));
    }

    public Class<E> getEnumClass() { return enumClass; }

    public boolean isValueAllowed(E v) { return true; }

    @Override
    public net.minecraft.network.chat.Component getName() {
        return net.minecraft.network.chat.Component.literal("EnumOption");
    }
}
