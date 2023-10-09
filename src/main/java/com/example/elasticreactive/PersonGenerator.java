package com.example.elasticreactive;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.devskiller.jfairy.Fairy;
import com.devskiller.jfairy.producer.person.Address;
import com.devskiller.jfairy.producer.person.Person;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomUtils;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Slf4j
class PersonGenerator {

    private final ObjectMapper objectMapper;
    private final ThreadLocal<Fairy> fairy;
    private final Scheduler scheduler = Schedulers.newParallel(PersonGenerator.class.getSimpleName());

    AtomicLong usernameIdExt = new AtomicLong();

    PersonGenerator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        fairy = ThreadLocal.withInitial(Fairy::create);
    }

    Flux<Doc> infinite() {
        return generateOne().repeat();
    }

    Flux<List<Doc>> infiniteMany() {
        return generateMany().repeat();
    }

    private Mono<Doc> generateOne() {
        return Mono
                .fromCallable(this::generate)
                .subscribeOn(scheduler);
    }

    private Mono<List<Doc>> generateMany() {
        return Mono
                .fromCallable(this::generateX)
                .subscribeOn(scheduler);
    }

    public List<Doc> generateX() {
        List<Doc> docs = new ArrayList<>();
        for(int i=0; i<100; i++) {
            docs.add(generate());
        }
        // log.info("generatedX ");
        return docs;
    }
    private Doc generate() {
        Person person = fairy.get().person();
        final String username = person.getUsername() + "_" + usernameIdExt.getAndIncrement();
        final ImmutableMap<String, Object> map = ImmutableMap.<String, Object>builder()
                .put("address", toMap(person.getAddress()))
                .put("firstName", person.getFirstName())
                .put("middleName", person.getMiddleName())
                .put("lastName", person.getLastName())
                .put("email", person.getEmail())
                .put("companyEmail", person.getCompanyEmail())
                .put("username", username)
                .put("password", person.getPassword())
                .put("sex", person.getSex())
                .put("telephoneNumber", person.getTelephoneNumber())
                .put("dateOfBirth", person.getDateOfBirth())
                .put("company", person.getCompany())
                .put("nationalIdentityCardNumber", person.getNationalIdentityCardNumber())
                .put("nationalIdentificationNumber", person.getNationalIdentificationNumber())
                .put("passportNumber", person.getPassportNumber())
                .build();
        try {
            final String json = objectMapper.writeValueAsString(map);
            //log.info("generated {}", username);
            return new Doc(username, json);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private ImmutableMap<String, Object> toMap(Address address) {
        return ImmutableMap.<String, Object>builder()
                .put("street", address.getStreet())
                .put("streetNumber", address.getStreetNumber())
                .put("apartmentNumber", address.getApartmentNumber())
                .put("postalCode", address.getPostalCode())
                .put("city", address.getCity())
                .put("lines", Arrays.asList(address.getAddressLine1(), address.getAddressLine2()))
                .build();
    }

}

@Value
class Doc {
    private final String username;
    private final String json;
}