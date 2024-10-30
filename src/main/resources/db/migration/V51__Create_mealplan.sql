-- Create Weekdays Table
CREATE TABLE `weekdays` (
                            `id` INT AUTO_INCREMENT PRIMARY KEY,
                            `weekday` VARCHAR(20) UNIQUE NOT NULL
);

-- Insert Weekdays Data
INSERT INTO `weekdays` (`weekday`) VALUES
                                       ('Monday'),
                                       ('Tuesday'),
                                       ('Wednesday'),
                                       ('Thursday'),
                                       ('Friday');

-- Create MealPlan Table
CREATE TABLE `meal_plan` (
                             `id` CHAR(36) PRIMARY KEY,
                             `week_number` INT NOT NULL,
                             `status` VARCHAR(50) NOT NULL CHECK (`status` IN ('OPEN', 'CLOSED', 'ARCHIVED')),
                             `menu_pdf` LONGBLOB
);

-- Create MealPlanUser Table
CREATE TABLE `meal_plan_user` (
                                  `id` CHAR(36) PRIMARY KEY,
                                  `useruuid` VARCHAR(36) NOT NULL,
                                  `meal_plan_id` CHAR(36) NOT NULL
);

-- Create MealChoice Table
CREATE TABLE `meal_choice` (
                               `id` CHAR(36) PRIMARY KEY,
                               `meal_plan_user_id` CHAR(36) NOT NULL,
                               `weekday` VARCHAR(20) NOT NULL,
                               `selected_meal_type` VARCHAR(50) CHECK (`selected_meal_type` IN ('meat', 'vegetarian', 'allergybowl')),
                               `reserve_meat` BOOLEAN DEFAULT FALSE,
                               `reserve_vegetarian` BOOLEAN DEFAULT FALSE,
                               `reserve_allergy_bowl` BOOLEAN DEFAULT FALSE,
                               `wants_breakfast` BOOLEAN DEFAULT FALSE,
                               `brings_guest` BOOLEAN DEFAULT FALSE,
                               `number_of_guest` INT DEFAULT 0,
                               `guest_wants_meat` INT DEFAULT 0,
                               `guest_wants_vegetarian` INT DEFAULT 0,
                               `guest_wants_allergy_bowl` INT DEFAULT 0,
                               CONSTRAINT `chk_reserve_if_selected` CHECK (
                                   (`selected_meal_type` IS NOT NULL AND
                                    `reserve_meat` = FALSE AND `reserve_vegetarian` = FALSE AND `reserve_allergy_bowl` = FALSE)
                                       OR (`selected_meal_type` IS NULL)
                                   )
);

-- Create MealPlanBuffer Table
CREATE TABLE `meal_plan_buffer` (
                                    `id` CHAR(36) PRIMARY KEY,
                                    `meal_plan_id` CHAR(36) NOT NULL,
                                    FOREIGN KEY (`meal_plan_id`) REFERENCES `meal_plan`(`id`)
);

-- Create Buffer Table
CREATE TABLE `buffer` (
                          `id` CHAR(36) PRIMARY KEY,
                          `meal_plan_buffer_id` CHAR(36) NOT NULL,
                          `weekday` VARCHAR(20) NOT NULL,
                          `buffer_meat` VARCHAR(10) NOT NULL,
                          `buffer_vegetarian` VARCHAR(10) NOT NULL,
                          `buffer_allergy_bowl` VARCHAR(10) NOT NULL,
                          FOREIGN KEY (`meal_plan_buffer_id`) REFERENCES `meal_plan_buffer`(`id`)
);


-- Optional: Create Indexes to Improve Query Performance
CREATE INDEX `idx_meal_choice_weekday` ON `meal_choice` (`weekday`);
CREATE INDEX `idx_buffer_weekday` ON `buffer` (`weekday`);