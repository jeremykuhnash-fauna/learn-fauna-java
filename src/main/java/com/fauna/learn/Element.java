package com.fauna.learn;

import com.faunadb.client.types.FaunaConstructor;
import com.faunadb.client.types.FaunaField;
import com.faunadb.client.types.FaunaIgnore;

public class Element {

    @FaunaField private String name;
    @FaunaField private String description;

    @FaunaConstructor
    public Element(@FaunaField("name") String name, @FaunaField("description") String description) {
        this.name = name;
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return "Element{" +
            "name='" + name + '\'' +
            ", description='" + description + '\'' +
            '}';
    }
}
