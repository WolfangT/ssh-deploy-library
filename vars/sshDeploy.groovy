#!/usr/bin/groovy

def call(String yamlName) {
    sshDeploy(yamlName, ".*", false)
}

def call(String yamlName, String stages) {
    sshDeploy(yamlName, stages, false)
}

def call(String yamlName, String stages, boolean dryRun) {
    def yaml = readYaml file: yamlName
    sshDeploy(yaml, stages, dryRun)
}

def call(yaml, String stages, boolean dryRun) {
    if(!yaml.config)
        error "config missing in the given yml file."
    if(!yaml.config.credentials_id)
        error "config->credentials_id is missing."

    def failedRemotes = []
    def retriedRemotes = []

    withCredentials([sshUserPrivateKey(credentialsId: yaml.config.credentials_id, keyFileVariable: 'identityFile', passphraseVariable: 'passphrase', usernameVariable: 'user')]) {

        if(!user && params.SSH_USER) {
            error "user name is null or empty, please check credentials_id or the SSH_USER param."
        }

        if(!identityFile && params.SSH_FILE) {
            error "KeyFile is null or empty, please check credentials_id or the SSH_FILE parameneter."
        }

        if(!passphrase && params.SSH_PASS) {
            error "password is null or empty, please check credentials_id or the SSH_PASS parameter."
        }

        yaml.steps.findAll { stageName, step -> stageName ==~ /${stages}/ }.each { stageName, step ->
            echo "${stageName} matched stages pattern"
            step.each {
                def remoteGroups = [:]
                def allRemotes = []

                it.remote_groups.each {
                    if(!yaml.remote_groups."$it") {
                        error "remotes groups are empty/invalid for the given stage: ${stageName}, command group: ${it}. Please check yml."
                    }
                    remoteGroups[it] = yaml.remote_groups."$it"
                }

                // Merge all the commands for the given group
                def commandGroups = [:]
                it.command_groups.each {
                    if(!yaml.command_groups."$it") {
                        error "command groups are empty/invalid for the given stage: ${stageName}, command group: ${it}. Please check yml."
                    }
                    commandGroups[it] = yaml.command_groups."$it"
                }

                def isSudo = false
                // Append user and identity for all the remotes.
                remoteGroups.each { remoteGroupName, remotes ->
                    allRemotes += remotes.collect { remote ->
                        if(!remote.host) {
                            throw IllegalArgumentException("host missing for one of the nodes in ${remoteGroupName}")
                        }
                        if(!remote.name)
                            remote.name = remote.host

                        if(params.SSH_USER) {
                            remote.user = params.SSH_USER
                            remote.identityFile = params.SSH_FILE
                            remote.passphrase = params.SSH_PASS
                            isSudo = true
                        } else {
                            remote.user = user
                            remote.identityFile = identityFile
                            remote.passphrase = passphrase
                        }

                        // For now we are settings host checking off.
                        remote.allowAnyHosts = true

                        remote.groupName = remoteGroupName
                        if(yaml.gateway) {
                            def gateway = [:]
                            gateway.name = yaml.gateway.name
                            gateway.host = yaml.gateway.host
                            gateway.allowAnyHosts = true

                            if(params.SSH_USER) {
                                gateway.user = params.SSH_USER
                                gateway.indentityFile = params.SSH_FILE
                                gateway.passphrase = params.SSH_PASS
                            } else {
                                gateway.user = user
                                gateway.identityFile = identityFile
                                gateway.passphrase = passphrase
                            }

                            remote.gateway = gateway
                        }
                        remote
                    }
                }

                // Execute in parallel.
                if(allRemotes) {
                    if(allRemotes.size() > 1) {
                        def stepsForParallel = allRemotes.collectEntries { remote ->
                            ["${remote.groupName}-${remote.name}" : transformIntoStep(dryRun, stageName, remote.groupName, remote, commandGroups, isSudo, yaml.config, failedRemotes, retriedRemotes)]
                        }
                        stage(stageName + " \u2609 Size: ${allRemotes.size()}") {
                            parallel stepsForParallel
                        }
                    } else {
                        def remote = allRemotes.first()
                        stage(stageName + "\n" + remote.groupName + "-" + remote.name) {
                            transformIntoStep(dryRun, stageName, remote.groupName, remote, commandGroups, isSudo, yaml.config, failedRemotes, retriedRemotes).call()
                        }
                    }
                }
            }
        }
    }
    return [failedRemotes, retriedRemotes]
}

private transformIntoStep(dryRun, stageName, remoteGroupName, remote, commandGroups, isSudo, config, failedRemotes, retriedRemotes) {
    return {
        def finalRetryResult = true
        commandGroups.each { commandGroupName, commands ->
            echo "Running ${commandGroupName} group of commands."
            commands.each { command ->
                command.each { commandName, commandList ->
                    commandList.each {
                        validateCommands(stageName, remoteGroupName, commandGroupName, commandName, it)
                        if(!dryRun) {
                            def stepName = "${stageName} -> ${remoteGroupName.replace("_", " -> ")} -> ${commandGroupName} -> ${remote.host}"
                            if (config.retry_with_prompt) {
                                retryWithPrompt([stepName: stepName]) {
                                    executeCommands(remote, stageName, remoteGroupName, commandGroupName, commandName, it, isSudo)
                                }
                            } else if(config.retry_and_return) {
                                def retryCount = config.retry_count ? config.retry_count.toInteger() : 2
                                def (isSuccessful, failedAtleastOnce) = retryAndReturn([retryCount: retryCount]) {
                                    executeCommands(remote, stageName, remoteGroupName, commandGroupName, commandName, it, isSudo)
                                }
                                if(!isSuccessful) {
                                    finalRetryResult = false
                                    if(!(stepName in failedRemotes)) {
                                        failedRemotes.add(stepName)
                                    }
                                } else if(failedAtleastOnce) {
                                    if(!(stepName in retriedRemotes)) {
                                        retriedRemotes.add(stepName)
                                    }
                                }
                            } else {
                                executeCommands(remote, stageName, remoteGroupName, commandGroupName, commandName, it, isSudo)
                            }
                        } else {
                            echo "DryRun Mode: Running ${commandName}."
                            echo "Remote: ${remote}"
                            echo "Command: ${it}"
                        }
                    }
                }
            }
        }
    }
}

private validateCommands(stageName, remoteGroupName, commandGroupName, commandName, command) {
    if(commandName in ["gets", "puts"]) {
        if(!command.from)
            error "${stageName} -> ${remoteGroupName} -> ${commandGroupName} -> ${commandName} -> from is empty or null."
        if(!command.into)
            error "${stageName} -> ${remoteGroupName} -> ${commandGroupName} -> ${commandName} -> into is empty or null."
    }
}

private executeCommands(remote, stageName, remoteGroupName, commandGroupName, commandName, command, isSudo) {
    switch (commandName) {
        case "commands":
            sshCommand remote: remote, command: command, sudo: isSudo
            break
        case "scripts":
            sshScript remote: remote, script: command
            break
        case "gets":
            sshGet remote: remote, from: command.from, into: command.into, override: command.override
            break
        case "puts":
            sshPut remote: remote, from: command.from, into: command.into
            break
        case "removes":
            sshRemove remote: remote, path: command
            break
        default:
            error "Invalid Command: ${stageName} -> ${remoteGroupName} -> ${commandGroupName} -> ${commandName}"
            break
    }
}
