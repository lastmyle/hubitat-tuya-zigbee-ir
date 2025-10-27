#!/usr/bin/env groovy
// Simple wrapper to run CodeNarc - dependencies loaded via classpath

def args = [
    '-basedir=.',
    '-includes=**/*.groovy',
    '-rulesetfiles=.codenarc',
    '-report=console'
] as String[]

// Load CodeNarc main class from the JAR on classpath
Class.forName('org.codenarc.CodeNarc').main(args)
