package com.example.elasticreactive;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;

@Document(indexName="city")
@Data
public class City {

    private @Id String name;
    private int population;

    public City(String name, int population) {
        this.name = name;
        this.population = population;
    }

}
