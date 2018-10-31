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
Map<String, Map<String, String>> github = globalValuesFactory.GITHUB_REPOS()

Map<String, String> params = [
    branch              : APP_BRANCH,
    tf_branch           : TF_BRANCH,
    action              : ACTION,
    environment         : ENVIRONMENT,
    clean_workspace     : CLEAN_WORKSPACE,
    selenium_hub_address: SELENIUM_HUB_ADDRESS,
    skip_tests          : SKIP_TESTS
]

Map<String, Boolean> isNewVersion = [:]

String deploymentJobName = "CVR_Deployment"
String deploymentBucketJobName = "${deploymentJobName}_bucket"
String jenkinsCtrlNodeLabel = "ctrl"
String jenkinsBuildLabel = "cvr"
String account = "dvsarecallsdev"
String accountId = ""
String group = "dvsarecallsdev"
String buildUser = ""
String project = "cvr"
String bucketPrefix = "terraformscaffold"
String projectBucketPrefix = ""
String s3DeploymentBucket = ""
String s3AssetsBucket = ""
String frontendAppName = "frontend"
String frontendApigwName = "${project}-${params.environment}-frontend"
String seleniumScreenshotsDir = "selenium-screenshots"
String recallsApiGwUrl = ""
String manifestVersion = ""
String assetsBasePath = ""
net.sf.json.JSON manifestContent


def failure(String reason) {
  currentBuild.result = "FAILURE"
  log.fatal(reason)
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
    stage ("Init") {
      failFast true
      agent { node { label "${jenkinsCtrlNodeLabel} && ${account}" } }
      stages {
        stage('Init: TF deployment bucket') {
          failFast true
          steps {
            script {
              wrap([$class: 'BuildUser']) { buildUser = env.BUILD_USER }
              Map output = awsFunctionsFactory.awsCli(
                  "aws sts get-caller-identity --query 'Account' --output text"
              )
              if (output.status) {
                failure("Failure while retrieving AWS account ID")
              }

              accountId = output.stdout.trim()
              projectBucketPrefix = "${project}-${accountId}-${globalValuesFactory.AWS_REGION}-${params.environment}"
              String releaseTimestamp = sh(script: 'date +%Y%m%d%H%M%S', returnStdout: true).trim()
              manifestVersion = "${releaseTimestamp}_${env.BUILD_NUMBER}"
              currentBuild.description = "CVR ${params.action} <br/> Branch: ${params.branch} <br/> Env: ${params.environment} <br/> Manifest: ${manifestVersion} <br/> ${buildUser}"
              if (!repoFunctionsFactory.checkoutGitRepo(
                  gitlab.cvr_terraform.url,
                  params.tf_branch,
                  gitlab.cvr_terraform.name,
                  globalValuesFactory.SSH_DEPLOY_GIT_CREDS_ID
              )) {
                failure("Failed to clone terraform repository ${gitlab.cvr_terraform.url}; branch: ${params.tf_branch}")
              }

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
                } else if (params.action != 'destroy') {
                  s3DeploymentBucket = "${projectBucketPrefix}-deployment"
                  log.info "s3 deployment bucket used: ${s3DeploymentBucket}"
                } //if
              } //dir
            } //script
          } //steps
        } //stage

        stage ("Init: Verify environment") {
          when { expression { params.action != 'destroy'} }
          failFast true
          steps {
            script {
              def manifestsListing = awsFunctionsFactory.awsCli("aws s3 ls ${s3DeploymentBucket}/manifests/")
              if (!manifestsListing.status && manifestsListing.stdout) {
                def latestManifestFile = manifestsListing.stdout.split('\n').sort().last().split()[3]
                log.info "LATEST MANIFEST FILE: ${latestManifestFile}"
                if (awsFunctionsFactory.awsCli(
                    "aws s3 cp s3://${s3DeploymentBucket}/manifests/${latestManifestFile} ${env.WORKSPACE}/manifests/manifest_${manifestVersion}.json"
                ).status) {
                  failure("Failure while copying latest manifest file")
                }
                log.info "Manifest file saved as ${env.WORKSPACE}/manifests/manifest_${manifestVersion}.json"
                manifestContent = readJSON(file: "${env.WORKSPACE}/manifests/manifest_${manifestVersion}.json")
              } else {
                log.info "No manifests present in ${s3DeploymentBucket}/manifests/"
                manifestContent = readJSON(text: "{}")
              }//if manifestsExist
            } //script
          } //steps
        } //stage
      } //stages
    } //stage init

    stage ("Build") {
      when  { expression { params.action == 'apply' }}
      failFast true
      parallel {
        stage("Build: CVR frontend") {
          agent { node { label "${jenkinsBuildLabel} && ${account}" } }
          steps {
            script {
              if (!repoFunctionsFactory.checkoutGitRepo(
                  github.cvr_app.url,
                  params.branch,
                  github.cvr_app.name,
                  globalValuesFactory.SSH_DEPLOY_GIT_CREDS_ID
              )) {
                failure("Failed to clone repository ${github.cvr_app.url}; branch: ${params.branch}")
              }

              dir(github.cvr_app.name) {
                Map output = repoFunctionsFactory.getRevision("${env.WORKSPACE}/${github.cvr_app.name}", frontendAppName)
                if(output.status) {
                  log.fatal output.stderr
                  failure("Failure while calculating ${frontendAppName} revision")
                }

                String revision = output.stdout
                isNewVersion[frontendAppName] = !manifestContent.frontend_version || manifestContent.frontend_version.split('_')[2] != revision
                if (!isNewVersion[frontendAppName]) {
                  log.info "Skipping ${frontendAppName} build, re-using ${manifestContent.frontend_version}"
                } else {
                  log.info "${frontendAppName} lambda revision to build: ${revision}"
                  String feVersion = "${manifestVersion}_${revision}".toString()

                  if (buildPackage(frontendAppName, feVersion)) {
                    failure("Failed to build CVR ${frontendAppName}")
                  } else {
                    manifestContent.put("frontend_version", feVersion)
                  } //if buildPackage
                } //if manifestContent
              } //dir
            } //script
          } //steps
        } //stage

        stage("Build: frontend assets") {
          agent { node { label "${jenkinsBuildLabel} && ${account}" } }
          steps {
            script {
              if (!repoFunctionsFactory.checkoutGitRepo(
                  github.cvr_app.url,
                  params.branch,
                  github.cvr_app.name,
                  globalValuesFactory.SSH_DEPLOY_GIT_CREDS_ID
              )) {
                failure("Failed to clone repository ${github.cvr_app.url}; branch: ${params.branch}")
              }

              String assetsVersion = null
              dir(github.cvr_app.name) {
                dir(frontendAppName) {
                  assetsVersion = readFile("assets.version")
                  if (!assetsVersion) {
                    failure('Failed to read frontend assets version required')
                  }
                }
              }
              log.info "Using assets ${assetsVersion}"

              if (!repoFunctionsFactory.checkoutGitRepo(
                  github.front_end.url,
                  assetsVersion,
                  github.front_end.name,
                  globalValuesFactory.SSH_DEPLOY_GIT_CREDS_ID,
                  false
              )) {
                failure('Failed to checkout frontend assets repository')
              } else {
                if(fileExists("${github.front_end.name}/dist/assets")) {
                  assetsBasePath = "${env.WORKSPACE}/${github.front_end.name}"
                  Map output = repoFunctionsFactory.getRevision(github.front_end.name)
                  if(output.status) {
                    log.fatal output.stderr
                    failure("Failure while calculating assets revision")
                  }

                  String revision = output.stdout
                  log.info "Assets revision ver: ${revision}"
                  isNewVersion['assets'] = !manifestContent.assets_version || manifestContent.assets_version.split('_')[2] != revision

                  if(!isNewVersion['assets']) {
                    log.info "Reusing assets version ${manifestContent.assets_version}"
                    if (awsFunctionsFactory.awsCli(
                        "aws s3 cp s3://${s3DeploymentBucket}/assets/assets-${manifestContent.assets_version}.zip ${assetsBasePath}/assets-${manifestContent.assets_version}.zip"
                    ).status) {
                      failure("Failure while fetching assets-${manifestContent.assets_version}.zip")
                    }

                  } else {
                    log.info "Building assets revision: ${revision}"
                    String assetsVer = "${manifestVersion}_${revision}".toString()
                    manifestContent.put("assets_version", assetsVer)
                    zip(dir: "${assetsBasePath}/dist/assets", zipFile: "${assetsBasePath}/assets-${manifestContent.assets_version}.zip")
                  } // if isNewVersion
                } // if fileExists
              } //if checkout
            } //script
          } //steps
        } //stage
      } //parallel
    } //stage build

    stage ("Deploy") {
      failFast true
      stages {
        stage("Deploy: Upload CVR frontend package") {
          when  { expression { params.action == 'apply' && isNewVersion[frontendAppName]}}
          agent { node { label "${jenkinsBuildLabel} && ${account}" } }
          steps {
            script {
              dir(github.cvr_app.name) {
                String[] files = findFiles(glob: "*-${frontendAppName}-${manifestContent.frontend_version}.zip")
                String distFile = ''

                if(files && files.size() == 1) {
                  distFile = files[0].trim()
                } else {
                  failure('Unable to locate CVR frontend package')
                }

                log.info "Found build file: " + distFile
                if(awsFunctionsFactory.copyFilesToS3(s3DeploymentBucket, '', "${distFile}")) {
                  failure("Failure while uploading ${frontendAppName} lambda package to s3")
                }
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
                String tfLambdaParams = "-var lambda_build_number=${manifestContent?.frontend_version}".toString()
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
                  Map output = awsFunctionsFactory.awsCli(
                      "aws apigateway get-rest-apis --query='items[?name == `${frontendApigwName}`].id | [0]' --output text"
                  )

                  if (output.status || !output.stdout || output.stdout.trim() == 'None') {
                    failure("Failed to fetch frontend gateway url")
                  }

                  s3AssetsBucket = "${projectBucketPrefix}-assets"
                  recallsApiGwUrl = "https://${output.stdout.trim()}.execute-api.${globalValuesFactory.AWS_REGION}.amazonaws.com/${params.environment}"

                  println "s3 assets bucket saved: ${s3AssetsBucket}"
                  println "Recalls API gateway URL saved: ${recallsApiGwUrl}"
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
              if(isNewVersion['assets']) {
                log.info "Deploying new assets ${manifestContent.assets_version}"
                dir(github.front_end.name) {
                  if (fileExists("${assetsBasePath}/dist/assets")) {
                    if (awsFunctionsFactory.copyFilesToS3(s3AssetsBucket, '', "${assetsBasePath}/dist/assets/", "--recursive")) {
                      failure("Failure while uploading assets to s3")
                    }

                    if (awsFunctionsFactory.copyFilesToS3(s3DeploymentBucket, 'assets/', "${assetsBasePath}/assets-${manifestContent.assets_version}.zip")) {
                      failure("Failure while uploading assets package to s3")
                    }
                  } else {
                    failure("Failed to locate compiled assets")
                  }
                } //dir
              } else {
                log.info "Reusing assets ${manifestContent.assets_version}"
                dir('tmp') {
                  unzip zipFile: "${assetsBasePath}/assets-${manifestContent.assets_version}.zip"
                  if (awsFunctionsFactory.copyFilesToS3(s3AssetsBucket, '', ".", "--recursive")) {
                    failure("Failure while uploading assets to s3 assets bucket")
                  }
                  deleteDir()
                } //dir
              } //if isNewVersion
            } //script
          } //steps
        } //stage

        stage("Deploy: Manifest") {
          agent { node { label "${jenkinsCtrlNodeLabel} && ${account}" } }
          when { expression { params.action == 'apply' } }
          steps {
            script {
              writeJSON(
                  file: "${env.WORKSPACE}/manifests/manifest_${manifestVersion}.json",
                  json: manifestContent
              )
              if(!fileExists("${env.WORKSPACE}/manifests/manifest_${manifestVersion}.json")) {
                failure("Error while trying to create manifest file")
              }

              if(awsFunctionsFactory.copyFilesToS3("${s3DeploymentBucket}", "manifests/", "${env.WORKSPACE}/manifests/manifest_${manifestVersion}.json")) {
                failure("Failure while uploading manifest to s3")
              }
            } //script
          } //steps
        } //stage Manifest
      } //parallel
    } //stage deploy

    stage ("Tests") {
      agent { node { label "${jenkinsCtrlNodeLabel} && ${account}" } }
      when  { expression { params.action == 'apply' && params.skip_tests != 'true'}}
      steps {
        script {
          if (!repoFunctionsFactory.checkoutGitRepo(
              github.cvr_app.url,
              params.branch,
              github.cvr_app.name,
              globalValuesFactory.SSH_DEPLOY_GIT_CREDS_ID)) {

            fail("Failed to checkout selenium tests from ${github.cvr_app.url}")
          } else {
            dir(github.cvr_app.name + "/selenium") {
              Integer testStatus = sh(
                      returnStatus: true,
                      script: """
                        mkdir -p ./${seleniumScreenshotsDir}

                        ./gradlew clean build \
                          -Dtest.baseUrl=${recallsApiGwUrl}\
                          -Dtest.platform=linux \
                          -Dtest.browserName=firefox \
                          -Dtest.gridEnabled=selenium \
                          -Dtest.javascript.enabled=yes \
                          -Dtest.gridUrl=${params.selenium_hub_address} \
                          -Dtest.screenshots.error.enabled=yes \
                          -Dtest.screenshots.error.folder=./${seleniumScreenshotsDir}
                      """)

              if (testStatus != 0) {

                archiveArtifacts(
                        artifacts: "${seleniumScreenshotsDir}/**",
                        onlyIfSuccessful: false,
                        allowEmptyArchive: true,
                )

                publishHTML target: [
                        alwaysLinkToLastBuild: false,
                        keepAll: true,
                        allowMissing: false,
                        reportDir   : "./build/reports/tests/selenium",
                        reportFiles : 'index.html',
                        reportName  : "Selenium Report #${env.BUILD_NUMBER}"
                ]

                failure("Selenium failure. Status code: ${testStatus}")
              } // if testStatus
            } //dir
          } //else
        } //script
      } //steps
    } //stage
  } //stages

  post {
    always {
      node("${jenkinsBuildLabel} && ${account}") {
        script {
          if (params.clean_workspace == "true") {
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
          } //if clean_workspace
        } //script
      } //node
      node("${jenkinsCtrlNodeLabel} && ${account}") {
        script {
          if (params.clean_workspace == "true") {
            if (fileExists(gitlab.cvr_terraform.name)) {
              dir(gitlab.cvr_terraform.name) {
                deleteDir()
              }
            }
          } //if clean_workspace
        } //script
      } //node
    } //always
    failure {
      script {
        slackSend(
            color: 'danger',
            message: "Job ${env.JOB_NAME} / ${env.BUILD_NUMBER} | FAILURE | Link <${env.BUILD_URL} | here>",
            channel: "cvr-jenkins-alerts"
        )
        log.fatal('Failure')
      } //script
    } //failure
  } //post
} //pipeline