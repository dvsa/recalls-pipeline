// using declarative pipeline: https://jenkins.io/doc/book/pipeline/syntax/
@Library('PipelineUtils@feature-BL-8651-build-pipeline-fe-lambda')
import dvsa.aws.mot.jenkins.pipeline.common.AWSFunctions
import dvsa.aws.mot.jenkins.pipeline.common.RepoFunctions
import dvsa.aws.mot.jenkins.pipeline.common.GlobalValues
import dvsa.aws.mot.jenkins.pipeline.common.ShellFunctions

// global variables
AWSFunctions awsFunctionsFactory = new AWSFunctions()
RepoFunctions repoFunctionsFactory = new RepoFunctions()
GlobalValues globalValuesFactory = new GlobalValues()
ShellFunctions shellFunctionsFactory = new ShellFunctions()
Map<String, Map<String, String>> gitlab = globalValuesFactory.GITLAB_REPOS()
Map<String, Map<String, String>> github = globalValuesFactory.GITHUB_REPOS()

Map<String, String> params = [
    branch         : APP_BRANCH,
    tf_branch      : TF_BRANCH,
    action         : ACTION,
    environment    : ENVIRONMENT,
    release_version: RELEASE_VERSION,
    clean_workspace: CLEAN_WORKSPACE
]

String deploymentJobName = "CVR_Deployment"
String deploymentBucketJobName = "${deploymentJobName}_bucket"
String jenkinsCtrlNodeLabel = "ctrl"
String jenkinsBuildLabel = "cvr"
String account = "dvsarecallsdev"
String group = "dvsarecallsdev"
String buildVersion = "${params.release_version}_b${env.BUILD_NUMBER}"
String buildUser = ""
String project = "cvr"
String bucketPrefix = "terraformscaffold"
String s3DeploymentBucket = ""
String s3AssetsBucket = ""
String frontendAppName = "frontend"

def failure(String reason) {
  currentBuild.result = "FAILURE"
  error(reason)
}

Integer buildPackage(String directory, String buildStamp) {
  dir (directory) {
    return sh (
        script: "npm install && npm run lint && npm test && npm run prod && mv ../cvr-${directory}.zip ../cvr-${directory}-${buildStamp}.zip",
        returnStatus: true
    )
  }
}

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
    disableConcurrentBuilds()
  }
  stages {
    stage('Init: TF deployment bucket') {
      failFast true
      agent { node { label "${jenkinsCtrlNodeLabel} && ${account}" } }
      steps {
        script {
          wrap([$class: 'BuildUser']) { buildUser = env.BUILD_USER }
          currentBuild.description = "CVR ${params.action} <br/> Branch: ${params.branch} <br/> Env: ${params.environment} <br/> ${buildUser}"
          repoFunctionsFactory.checkoutGitRepo(
              gitlab.cvr_terraform.url,
              params.tf_branch,
              gitlab.cvr_terraform.name,
              globalValuesFactory.SSH_DEPLOY_GIT_CREDS_ID
          )
          dir(gitlab.cvr_terraform.name) {
            if (awsFunctionsFactory.terraformScaffold(
                project,
                params.environment,
                group,
                globalValuesFactory.AWS_REGION,
                '',
                deploymentBucketJobName,
                env.BUILD_NUMBER,
                "deployment-bucket",
                bucketPrefix,
                params.action
            )) {

              failure('Failed to run TF Scaffold on deployment-bucket component')
            } else if (params.action == 'apply') {
              String output = awsFunctionsFactory.terraformScaffoldOutput(
                  project,
                  params.environment,
                  group,
                  globalValuesFactory.AWS_REGION,
                  "",
                  "${deploymentBucketJobName}_output",
                  env.BUILD_NUMBER,
                  "deployment-bucket",
                  bucketPrefix
              )

              s3DeploymentBucket = output.find(~/lambda_s3_bucket_id = (\S+)(?:$|\s)/) { match ->
                if (!match) { //TODO extract to pipeline utils
                  failure('lambda_s3_bucket_id cannot be found in TF outputs')
                  return null
                }

                return match[1]
              } //find

              println "s3 deployment bucket saved: ${s3DeploymentBucket}"
            } //if
          } //dir
        } //script
      } //steps
    } //stage

    stage ("Build") {
      when  { expression { params.action == 'apply' }}
      failFast true
      stages { //TODO parallel ? But build fe assets would have to checkout app on its own
        stage("Build: CVR frontend") {
          agent { node { label "${jenkinsBuildLabel} && ${account}" } }
          steps {
            script {
              repoFunctionsFactory.checkoutGitRepo(github.cvr_app.url, params.branch, github.cvr_app.name, globalValuesFactory.SSH_DEPLOY_GIT_CREDS_ID)
              dir(github.cvr_app.name) {
                if (buildPackage(frontendAppName, buildVersion)) {
                  failure("Failed to build CVR ${frontendAppName}")
                } //if
              } //dir
            } //script
          } //steps
        } //stage

        stage("Build: frontend assets") {
          agent { node { label "${jenkinsBuildLabel} && ${account}" } }
          steps {
            script {
              String assetsVersion = null
              dir(github.cvr_app.name) {
                dir(frontendAppName) {
                  assetsVersion = readFile("assets.version")
                  if (!assetsVersion) {
                    failure('Failed to read frontend assets version required')
                  }
                }
              }
              if (repoFunctionsFactory.checkoutGitRepo(
                  github.front_end.url,
                  assetsVersion,
                  github.front_end.name,
                  globalValuesFactory.SSH_DEPLOY_GIT_CREDS_ID,
                  false
              )) {
                dir(github.front_end.name) {
                  if (sh ( script: "npm install && npm run build-production", returnStatus: true)) {
                    failure('Failed to build frontend assets')
                  } //if sh
                } //dir
              } else {
                failure('Failed to checkout frontend assets repository')
              } //if checkout
            } //script
          } //steps
        } //stage
      } //parallel
    } //stage

    stage ("Deploy") {
      failFast true
      stages {
        stage("Deploy: Upload CVR frontend package") {
          when  { expression { params.action == 'apply' }}
          agent { node { label "${jenkinsBuildLabel} && ${account}" } }
          steps {
            script {
              dir(github.cvr_app.name) {
                Map<String, String> output = shellFunctionsFactory.findFiles("*-${frontendAppName}-${buildVersion}.zip")
                String distFile = ''

                if(output.status == 0 && output.stdout) {
                  distFile = output.stdout.trim()
                } else {
                  failure('Unable to locate CVR frontend package')
                }

                println "Found build file: " + distFile
                awsFunctionsFactory.copyFilesToS3(s3DeploymentBucket, '', distFile)
              } //dir
            } //script
          } //steps
        } //stage

        stage("Deploy: Terraform CVR") {
          agent { node { label "${jenkinsCtrlNodeLabel} && ${account}" } }
          steps {
            script {
              repoFunctionsFactory.checkoutGitRepo(gitlab.cvr_terraform.url, params.tf_branch, gitlab.cvr_terraform.name, globalValuesFactory.SSH_DEPLOY_GIT_CREDS_ID)
              dir(gitlab.cvr_terraform.name) {
                String tfLambdaParams =  "-var lambda_build_number=${buildVersion}".toString()
                if (awsFunctionsFactory.terraformScaffold(
                    project,
                    params.environment,
                    group,
                    globalValuesFactory.AWS_REGION,
                    tfLambdaParams,
                    deploymentJobName,
                    env.BUILD_NUMBER,
                    "cvr",
                    bucketPrefix,
                    params.action
                )) {

                  failure('Failed to run TF Scaffold on cvr component')
                } else if(params.action == 'apply') {
                  String output = awsFunctionsFactory.terraformScaffoldOutput(
                      project,
                      params.environment,
                      group,
                      globalValuesFactory.AWS_REGION,
                      "",
                      "${deploymentJobName}_output",
                      env.BUILD_NUMBER,
                      "cvr",
                      bucketPrefix
                  )

                  s3AssetsBucket = output.find(~/lambda_s3_assets_bucket_id = (\S+)(?:$|\s)/) { match ->
                    if (!match) { //TODO extract to pipeline utils
                      failure('lambda_s3_assets_bucket_id cannot be found in TF outputs')
                      return null
                    }

                    return match[1]
                  } //find

                  println "s3 assets bucket saved: ${s3AssetsBucket}"
                }//if
              } //dir
            } //script
          } //steps
        } //stage

        stage("Deploy: Upload CVR frontend assets") {
          when  { expression { params.action == 'apply' }}
          agent { node { label "${jenkinsBuildLabel} && ${account}" } }
          steps {
            script {
              dir(github.front_end.name) {
                if (fileExists("dist/assets")) {
                  awsFunctionsFactory.copyFilesToS3(s3AssetsBucket, '', "dist/assets/", "--recursive")
                } else {
                  failure("Failed ")
                }
              } //dir
            } //script
          } //steps
        } //stage
      } //parallel
    } //stage
  } //stages

  post {
    always {
      node("${jenkinsBuildLabel} && ${account}") {
        script {
          if (fileExists(github.front_end.name)) {
            dir(github.front_end.name) {
              deleteDir()
            }
          }
          if (fileExists(github.cvr_app.name)) {
            dir(github.cvr_app.name) {
              deleteDir()
            }
          }
        } //script
      } //node
      node("${jenkinsCtrlNodeLabel} && ${account}") {
        script {
          if (fileExists(gitlab.cvr_terraform.name)) {
            dir(gitlab.cvr_terraform.name) {
              deleteDir()
            }
          }
        } //script
      } //node
    } //always
    failure {
      script {
        slackSend(
            color: 'danger',
            message: "Job ${env.JOB_NAME} / ${env.BUILD_NUMBER} | FAILURE | Link <${env.BUILD_URL} | here>",
            channel: "cvr"
        )
        log.fatal('failure')
      } //script
    } //failure
  } //post
} //pipeline