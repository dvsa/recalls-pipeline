// using declarative pipeline: https://jenkins.io/doc/book/pipeline/syntax/
@Library('PipelineUtils@master')
import dvsa.aws.mot.jenkins.pipeline.common.AWSFunctions
import dvsa.aws.mot.jenkins.pipeline.common.RepoFunctions
import dvsa.aws.mot.jenkins.pipeline.common.GlobalValues

// global variables
AWSFunctions awsFunctionsFactory = new AWSFunctions()
RepoFunctions repoFunctionsFactory = new RepoFunctions()
GlobalValues globalValuesFactory = new GlobalValues()
Map<String, Map<String, String>> gitlab = globalValuesFactory.GITLAB_REPOS()

Map<String, String> params = [
  exclusions_raw : "${EXCLUDED_ENVS}"
]

List<String> envs_list      = []
List<String> exclusions     = []
List<String> lull_list      = []
String project              = 'cvr'
String tf_branch            = 'master'
String jenkinsCtrlNodeLabel = "ctrl"
String account              = "dvsarecallsdev"
String buildUser            = ""
def all_envs_raw
def account_id

// PIPELINE --------------------------------------------------------------------------
pipeline {
  agent none
  options {
    ansiColor('xterm')
    timestamps()
    timeout(time: 1, unit: 'HOURS')
    buildDiscarder(
        logRotator(
            daysToKeepStr: '7'
        )
    )
  }

  stages {

    stage('pick DEV envs') {
      failFast true
      agent { node { label "${jenkinsCtrlNodeLabel} && ${account}" } }
      steps {
        script {
          wrap([$class: 'BuildUser']) { buildUser = env.BUILD_USER }
          if (buildUser == null) {
            buildUser = 'auto-start'
          }
          currentBuild.description = "DESTROY! | USER: ${buildUser}"
          env.WORKSPACE = pwd()
          deleteDir()

          if (!repoFunctionsFactory.checkoutGitRepo(
              gitlab.cvr_terraform.url,
              tf_branch,
              gitlab.cvr_terraform.name,
              globalValuesFactory.SSH_DEPLOY_GIT_CREDS_ID
          )) {
            failure("Failed to clone terraform repository ${gitlab.cvr_terraform.url}; branch: ${tf_branch}")
          }

          dir('terraform/etc/') {
            all_envs_raw = findFiles(glob: 'env_*.tfvars')
          }
          for (env in all_envs_raw) {
            String env_name = env.name.toString().split('_')[2].minus('.tfvars')
            envs_list << env_name
          }
          String[] all_exclusions = params.exclusions_raw.split(',')
          for (String excl in all_exclusions) {
            exclusions << ("${excl}").toString().trim()
          }
          log.info("ALL ENVS: ${envs_list}")
          log.info("EXCLUSIONS: ${exclusions}")
          for (String env in envs_list) {
            log.info("> Verifying environment: ${env}")
            if (! exclusions.contains(env)) {
              log.info(">> Marked to be lulled")
              lull_list << env 
            }
          }
          log.info("[INFO] Environments to be lulled: ${lull_list}")
        } //script
      } //steps
    } //stage pick DEV envs

    stage('lull DEV envs') {
      failFast true
      agent { node { label "${jenkinsCtrlNodeLabel} && ${account}" } }
      steps {
        script {
          Map output = awsFunctionsFactory.awsCli(
            "aws sts get-caller-identity --query 'Account' --output text"
          )
          if (output.status) {
            failure("Failure while retrieving AWS account ID")
          }
          account_id = output.stdout.trim()

          for (String env in lull_list) {
            projectBucketPrefix = "${project}-${account_id}-${globalValuesFactory.AWS_REGION}-${env}"
            s3DeploymentBucket = "${projectBucketPrefix}-deployment"
            
            def bucket_info = awsFunctionsFactory.awsCli("aws s3 ls s3://${s3DeploymentBucket} --region ${globalValuesFactory.AWS_REGION}")
            if (bucket_info.status == 0 ) {
              log.info("> Destroying '${env}' environment...")
              build job: 'CVR_build', parameters: [string(name: 'ENVIRONMENT', value: env), string(name: 'ACTION', value: 'destroy')], propagate: false
            } else { 
              log.info("> Environment '${env}' does not exist. Skipping...")
            }
          } //for
        } //script
      } //steps
    } //stage lull DEV envs

  } //stages
} //pipeline