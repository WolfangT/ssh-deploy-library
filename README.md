# ssh-deploy-library

Jenkins Pipeline Library - A Yaml wrapper on top of ssh-steps-plugin.

More about on this [blog](https://engineering.cerner.com/blog/ssh-steps-for-jenkins-pipeline/)

This is just an example library.

## fork

Forked with the objetive to allow the deploy script to use ssh credentials
instead of username and password ones.

This is in my non security expert opinion, more secure and cleaner,
as the same private key can be used to set up to gain secure access
to multiple servers without having to set up the same password on all of them

## ssh keys

This will now accept a `SSH Username with private key` type of credential,
but it will have to be from the "older" OpenSHH format, otherwise it will fail
you can generate a key of that kind using

    ssh-keygen -t rsa -b 4096 -m PEM

## stages pattern

Added a fucntionality to pass a regex pattern that mathes stages to be executed,
this can be usefull to define both testing and production deployments stages
and indicate on the Jenkinsfile for example in wich deployment stage to execute
based on the current branch or other factor

    sshDeploy('dev/deploy.sh') # Executes all stages

    sshDeploy('dev/deploy.sh', 'production') # Executes only the production stage

    sshDeploy('dev/deploy.sh', 'testing.*') # Executes stages that start with 'testing'

## Sample YML file:


```yml
config:
  credentials_id: JENKINS_SSH
  # retry_with_prompt: true
  # retry_and_return: true
  # retry_count: 3

remote_groups:
  r_group_1:
    - name: node01
      host: node01.abc.net
    - name: node02
      host: node02.abc.net
  r_group_2:
    - name: node03
      host: node03.abc.net

command_groups:
  c_group_1:
    - commands:
        - 'ls -lrt'
        - 'whoami'
    - scripts:
        - 'test.sh'
  c_group_2:
    - gets:
        - from: 'test.sh'
          into: 'test_new.sh'
          override: true
    - puts:
        - from: 'test.sh'
          into: '.'
    - removes:
        - 'test.sh'

steps:
  production:
    - remote_groups:
        - r_group_1
      command_groups:
        - c_group_1
  testing_node03:
    - remote_groups:
        - r_group_2
      command_groups:
        - c_group_2
```
