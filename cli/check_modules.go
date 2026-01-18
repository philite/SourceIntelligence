package main

import (
	"context"
	"fmt"
	"log"
	"strings"

	"github.com/neo4j/neo4j-go-driver/v5/neo4j"
)

func main() {
	ctx := context.Background()
	driver, err := neo4j.NewDriverWithContext(
		"bolt://localhost:7687",
		neo4j.BasicAuth("memgraph", "sourceintel", ""),
	)
	if err != nil {
		log.Fatal(err)
	}
	defer driver.Close(ctx)

	session := driver.NewSession(ctx, neo4j.SessionConfig{})
	defer session.Close(ctx)

	result, err := session.Run(ctx, "CALL mg.procedures() YIELD name RETURN name", nil)
	if err != nil {
		log.Fatal(err)
	}

	fmt.Println("Searching for JSON related procedures:")
	found := false
	for result.Next(ctx) {
		name, _ := result.Record().Get("name")
		ns := name.(string)
		if strings.Contains(strings.ToLower(ns), "json") {
			fmt.Println("-", ns)
			found = true
		}
	}
	if !found {
		fmt.Println("No JSON procedures found!")
	}
}
