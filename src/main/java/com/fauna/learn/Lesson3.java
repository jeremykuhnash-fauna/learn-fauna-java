/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.fauna.learn;

/*
 * These imports are for basic functionality around logging and JSON handling and Futures.
 * They should best be thought of as a convenience items for our demo apps.
 */

import com.faunadb.client.query.Expr;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.JsonProcessingException;

import com.google.common.base.Optional;

import java.util.Collection;
import java.util.List;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Collectors;

/*
 * These are the required imports for Fauna.
 *
 * For these examples we are using the 2.1.0 version of the JVM driver. Also notice that we aliasing
 * the query and values part of the API to make it more obvious we we are using Fauna functionality.
 *
 */
import com.faunadb.client.*;
import com.faunadb.client.types.*;

import static com.faunadb.client.query.Language.*;
import static java.util.stream.Collectors.toList;

public class Lesson3 {

    // The fauna field data is the field with query data.  Use a static const to refer to the string.
    // i.e. {"ref": ..., "ts": ..., data":...}
    private static String FAUNA_DATA = "data";

    private static final Logger logger = LoggerFactory.getLogger(Lesson1.class);

    private static ObjectMapper mapper = getMapper();

    private static ObjectMapper getMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        return mapper;
    }

    private static String toPrettyJson(Value value) throws JsonProcessingException {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    }

    private static String createDatabase(String sURL, String secret, String dbName) throws Exception {
        /*
         * Create an admin connection to FaunaDB.
         */
        FaunaClient adminClient = FaunaClient.builder()
                .withEndpoint(sURL)
                .withSecret(secret)
                .build();
        logger.info("Connected to FaunaDB as Admin!");

        /*
         * Create a database
         */
        Value result = adminClient.query(
                Arr(
                        If(
                                Exists(Database(dbName)),
                                Delete(Database(dbName)),
                                Value(true)
                        ),
                        CreateDatabase(Obj("name", Value(dbName)))
                )
        ).get();
        logger.info("Created database: {} :: \n{}", dbName, toPrettyJson(result));

        /*
         * Create a key specific to the database we just created. We will use this to
         * create a new client we will use in the remainder of the examples.
         */
        result = adminClient.query(
                CreateKey(Obj("database", Database(Value(dbName)), "role", Value("server")))
        ).get();
        String dbSecret = result.at("secret").to(String.class).get();
        logger.info("DB {} secret: {}", dbName, secret);

        adminClient.close();
        logger.info("Disconnected from FaunaDB as Admin!");

        return dbSecret;
    }

    private static FaunaClient createDBClient(String dcURL, String secret) throws Exception {
        /*
         * Create the DB specific DB client using the DB specific key just created.
         */
        FaunaClient client = FaunaClient.builder()
                .withEndpoint(dcURL)
                .withSecret(secret)
                .build();
        logger.info("Connected to FaunaDB as server");

        return client;
    }

    private static void createSchema(FaunaClient client) throws Exception {
        /*
         * Create an class to hold customers
         */
        Value result = client.query(
                CreateClass(Obj("name", Value("customers")))
        ).get();
        logger.info("Created customers class :: {}", toPrettyJson(result));

        /*
         * Create two indexes here. The first index is to query customers when you know specific id's.
         * The second is used to query customers by range. Examples of each type of query are presented
         * below.
         */
        result = client.query(
                Arr(
                        CreateIndex(
                                Obj(
                                        "name", Value("customer_by_id"),
                                        "source", Class(Value("customers")),
                                        "unique", Value(true),
                                        "terms", Arr(Obj("field", Arr(Value(FAUNA_DATA), Value("id"))))
                                )
                        ),
                        CreateIndex(
                                Obj(
                                        "name", Value("customer_id_filter"),
                                        "source", Class(Value("customers")),
                                        "unique", Value(true),
                                        "values", Arr(
                                                Obj("field", Arr(Value(FAUNA_DATA), Value("id"))),
                                                Obj("field", Arr(Value("ref")))
                                        )
                                )
                        )
                )
        ).get();
        logger.info("Created \'customer_by_id\' index & \'customer_id_filter\' index :: {}", toPrettyJson(result));
    }

    private static void createCustomers(FaunaClient client) throws Exception {
        /*
         * Create 20 customer records with ids from 1 to 20
         */
        List<Integer> range = IntStream
                .rangeClosed(1, 20)
                .boxed()
                .collect(toList());

        Value result = client.query(
                Map(Value(range),
                        Lambda(Value("id"),
                                Create(
                                        Class(Value("customers")),
                                        Obj(FAUNA_DATA,
                                                Obj("id", Var("id"), "balance", Multiply(Var("id"), Value(10)))
                                        )
                                )
                        )
                )
        ).get();

        Collection<Customer> customerCollection = result.asCollectionOf(Customer.class).get();
        logger.info("createCustomers customers size: " + customerCollection.size());
        customerCollection.forEach(customer -> logger.info("createCustomers next customer: " + customer));
        logger.info("Created list of 20 customers: \n{}", toPrettyJson(result));
    }

    private static void saveAllCustomers(FaunaClient client, List<Customer> customers) throws Exception {

        //FQL Lambda Variable for each customer
        String NXT_CUSTOMER = "NEXT_CUSTOMER";

        Value result = client.query(
                Foreach(
                        Value(customers),
                        Lambda(Value(NXT_CUSTOMER),
                                Create(
                                        Class(Value("customers")),
                                        Obj(FAUNA_DATA, Var(NXT_CUSTOMER))
                                )
                        )

                )
        ).get();

        logger.info("Created list of customers from customers: \n{}", toPrettyJson(result));

        Collection<Customer> customerCollection = result.asCollectionOf(Customer.class).get();
        logger.info("saveAllCustomers customers size: " + customerCollection.size());
        customerCollection.forEach(customer -> logger.info("saveAllCustomers next customer: " + customer));
    }

    private static void readCustomer(FaunaClient client, int custID) throws Exception {
        /*
         * Read the customer we just created
         */
        Value result = client.query(
                Select(Value(FAUNA_DATA), Get(Match(Index("customer_by_id"), Value(custID))))
        ).get();
        logger.info("Read \'customer\' {}: \n{}", custID, toPrettyJson(result));


        Customer c1 = result.to(Customer.class).get();
        logger.info("Read customer: " + c1);
    }

    private static void readThreeCustomers(FaunaClient client, int custID_1, int custID_2, int custID_3) throws Exception {
        /*
         * Here is a more general use case where we retrieve multiple class references
         * by id and return the actual data underlying them.
         */

        //FQL Lambda Variable for each customer refrence id
        String CUST_REF_ID = "CUST_REF_ID";

        Value result = client.query(
                Select(Value(FAUNA_DATA), Map(
                        Paginate(
                                Union(
                                        Match(Index("customer_by_id"), Value(custID_1)),
                                        Match(Index("customer_by_id"), Value(custID_2)),
                                        Match(Index("customer_by_id"), Value(custID_3))
                                )
                        ),
                        Lambda(Value(CUST_REF_ID), Get(Var(CUST_REF_ID)))
                ))
        ).get();

        Collection<Customer> customerCollection = result.asCollectionOf(Customer.class).get();

        logger.info("readThreeCustomers customers size: " + customerCollection.size());
        customerCollection.forEach(customer -> logger.info("readThreeCustomers next customer: " + customer));
    }

    private static void readListOfCustomers(FaunaClient client, List<Integer> custList) throws Exception {
        /*
         * Finally a much more general use case where we can supply any number of id values
         * and return the data for each.
         */

        //FQL Lambda Variable for each customer id
        String CUST_ID = "CUST_ID";

        //FQL Lambda Variable for each customer refrence id
        String CUST_REF_ID = "CUST_REF";

        Value result = client.query(
                Select(Value(FAUNA_DATA),
                        Map(
                                Paginate(
                                        Union(
                                                Map(
                                                        Value(custList),
                                                        Lambda(Value(CUST_ID), Match(Index("customer_by_id"), Var(CUST_ID)))
                                                )
                                        )
                                ),
                                Lambda(Value(CUST_REF_ID), Select(Value(FAUNA_DATA), Get(Var(CUST_REF_ID))))
                        )
                )
        ).get();

        Collection<Customer> customerCollection = result.asCollectionOf(Customer.class).get();
        logger.info("readListOfCustomers customers size: " + customerCollection.size());
        customerCollection.forEach(customer -> logger.info("readListOfCustomers next customer: " + customer));
    }

    private static void readCustomersLessThan(FaunaClient client, int maxCustID) throws Exception {
        /*
         * In this example we use the values based filter 'customer_id_filter'.
         * using this filter we can query by range. This is an example of returning
         * all the values less than(<) or before 5. The keyword 'after' can replace
         * 'before' to yield the expected results.
         */
        Value result = client.query(
                Select(Value(FAUNA_DATA),
                        Map(
                                Paginate(Match(Index("customer_id_filter"))).before(Value(maxCustID)),
                                Lambda(Value("x"), Select(Value("data"), Get(Select(Value(1), Var("x")))))
                        )
                )
        ).get();

        Collection<Customer> customerCollection = result.asCollectionOf(Customer.class).get();
        logger.info("readCustomersLessThan customers size: " + customerCollection.size());
        customerCollection.forEach(customer -> logger.info("readCustomersLessThan next customer: " + customer));
    }

    private static void readCustomersBetween(FaunaClient client, int minCustID, int maxCustID) throws Exception {
        /*
         * Extending the previous example to show getting a range between two values.
         */
        Value result = client.query(
                Select(Value(FAUNA_DATA), Map(
                        Filter(Paginate(Match(Index("customer_id_filter"))).before(Value(maxCustID)),
                                Lambda(Value("y"), LTE(Value(minCustID), Select(Value(0), Var("y"))))),
                        Lambda(Value("x"), Select(Value(FAUNA_DATA), Get(Select(Value(1), Var("x")))))
                        )
                )
        ).get();

        Collection<Customer> customerCollection = result.asCollectionOf(Customer.class).get();
        logger.info("readCustomersBetween customers size: " + customerCollection.size());
        customerCollection.forEach(customer -> logger.info("readCustomersBetween next customer: " + customer));
    }

    private static void readAllCustomers(FaunaClient client) throws Exception {
        /*
         * Read all the records that we created.
         * Use a small'ish page size so that we can demonstrate a paging example.
         *
         * NOTE: after is inclusive of the value.
         */
        Optional<Value> dataPage = Optional.absent();
        Optional<Value> cursorPos = Optional.absent();
        Expr paginationExpr;

        int pageSize = 8;
        do {

            if (!cursorPos.isPresent()) {
                paginationExpr = Paginate(Match(Index("customer_id_filter"))).size(Value(pageSize));
            } else {
                paginationExpr = Paginate(Match(Index("customer_id_filter"))).after(cursorPos.get()).size(Value(pageSize));
            }

            Value result = client.query(
                    Map(
                            paginationExpr,
                            Lambda(Value("x"), Select(Value(FAUNA_DATA), Get(Select(Value(1), Var("x")))))
                    )
            ).get();

            //{"after":..., "before":..., "data":...}
            Collection<Customer> customerCollection = result.get(Field.at(FAUNA_DATA)).asCollectionOf(Customer.class).get();
            logger.info("readAllCustomers customers size: " + customerCollection.size());
            customerCollection.forEach(customer -> logger.info("readAllCustomers next customer: " + customer));

            cursorPos = result.getOptional(Field.at("after"));
            if (cursorPos.isPresent()) {
                logger.info("After: {}", toPrettyJson(cursorPos.get()));
            }

        } while (cursorPos.isPresent());
    }


    public static void main(String[] args) throws Exception {
        String dcURL = "http://127.0.0.1:8443";
        String secret = "secret";
        String dbName = "LedgerExample";

        String dbSecret = createDatabase(dcURL, secret, dbName);

        FaunaClient client = createDBClient(dcURL, dbSecret);

        createSchema(client);

        Customer c1 = new Customer(101, 200);
        Customer c2 = new Customer(102, 300);
        Customer c3 = new Customer(103, 400);
        Customer c4 = new Customer(104, 500);
        List<Customer> customerList = Arrays.asList(c1, c2, c3, c4);
        saveAllCustomers(client, customerList);

        createCustomers(client);

        readCustomer(client, 1);

        readThreeCustomers(client, 1, 3, 7);

        int[] customers = {1, 3, 6, 7};
        List<Integer> custList = Arrays.stream(customers).boxed().collect(toList());
        readListOfCustomers(client, custList);

        readCustomersLessThan(client, 5);

        readCustomersBetween(client, 5, 11);

        readAllCustomers(client);

        /*
         * Just to keep things neat and tidy, close the client connections
         */
        client.close();
        logger.info("Disconnected from FaunaDB as server for DB {}!", dbName);

        // add this at the end of execution to make things shut down nicely
        System.exit(0);
    }
}
